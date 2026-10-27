package com.jizza.engine.devices.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.device.DeviceActionCatalog;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandListener;
import com.jizza.engine.api.device.DeviceCommandRequest;
import com.jizza.engine.api.device.DeviceCommandStatus;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.device.DevicePlatform;
import com.jizza.engine.api.device.DeviceTarget;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationPriority;
import com.jizza.engine.api.error.AutonomyDeniedException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.RateLimitedException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.tool.ToolSchemaValidator;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.devices.catalog.DeviceExtras;
import com.jizza.engine.devices.config.DeviceProperties;
import com.jizza.engine.devices.crypto.ServerKeys;
import com.jizza.engine.devices.gateway.DeviceHub;
import com.jizza.engine.devices.protocol.Canonical;
import com.jizza.engine.devices.protocol.MessageIds;
import com.jizza.engine.devices.protocol.Protocol;
import com.jizza.engine.devices.push.PushDispatcher;
import com.jizza.engine.persistence.entity.DeviceCommandEntity;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.entity.DeviceFileEntity;
import com.jizza.engine.persistence.repository.DeviceCommandRepository;
import com.jizza.engine.persistence.repository.DeviceFileRepository;
import com.jizza.engine.persistence.repository.DeviceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The command broker (see {@link DeviceCommands}). Every command is a row before it is sent, and a
 * device only ever hears about it after that row is committed, so a fast device can never answer a
 * command the database does not know yet.
 */
@Service
public class CommandBroker implements DeviceCommands {

  private static final Logger log = LoggerFactory.getLogger(CommandBroker.class);

  static final Set<String> OPEN = Set.of("queued", "dispatched", "accepted", "running");
  static final Set<String> NOT_ACCEPTED = Set.of("queued", "dispatched");
  /** A dispatched command a polling device has not accepted is offered again after this. */
  private static final Duration REOFFER_AFTER = Duration.ofSeconds(60);
  private static final int MAX_PROGRESS = 500;

  private final DeviceCommandRepository commands;
  private final DeviceRepository devices;
  private final DeviceFileRepository files;
  private final DeviceActionCatalog catalog;
  private final EncryptionService encryption;
  private final ServerKeys serverKeys;
  private final DeviceHub hub;
  private final PushDispatcher push;
  private final AuditLog audit;
  private final DeviceProperties props;
  private final TransactionTemplate transactions;
  private final ObjectProvider<KillSwitch> killSwitch;
  private final ObjectProvider<NotificationBus> notifications;
  private final ObjectProvider<RealtimeGateway> realtime;
  private final ObjectProvider<DeviceCommandListener> listeners;
  private final Clock clock = Clock.systemUTC();
  private final Map<UUID, CompletableFuture<Void>> waiting = new ConcurrentHashMap<>();

  public CommandBroker(DeviceCommandRepository commands, DeviceRepository devices,
                       DeviceFileRepository files, DeviceActionCatalog catalog,
                       EncryptionService encryption, ServerKeys serverKeys, DeviceHub hub,
                       PushDispatcher push, AuditLog audit, DeviceProperties props,
                       TransactionTemplate transactions, ObjectProvider<KillSwitch> killSwitch,
                       ObjectProvider<NotificationBus> notifications,
                       ObjectProvider<RealtimeGateway> realtime,
                       ObjectProvider<DeviceCommandListener> listeners) {
    this.commands = commands;
    this.devices = devices;
    this.files = files;
    this.catalog = catalog;
    this.encryption = encryption;
    this.serverKeys = serverKeys;
    this.hub = hub;
    this.push = push;
    this.audit = audit;
    this.props = props;
    this.transactions = transactions;
    this.killSwitch = killSwitch;
    this.notifications = notifications;
    this.realtime = realtime;
    this.listeners = listeners;
  }

  // ── Submitting ───────────────────────────────────────────────────────────────

  @Override
  // A refusal is an answer, not a fault: it must not spoil the caller's transaction (a run's step).
  @Transactional(noRollbackFor = {com.jizza.engine.api.error.ValidationException.class,
      com.jizza.engine.api.error.NotFoundException.class})
  public DeviceCommandDto submit(DeviceCommandRequest req) {
    if (req.idempotencyKey() != null) {
      Optional<DeviceCommandEntity> existing = commands.findByIdempotencyKey(req.idempotencyKey());
      if (existing.isPresent()) return toDto(existing.get());
    }
    KillSwitch ks = killSwitch.getIfAvailable();
    if (ks != null && ks.paused(req.userId())) {
      throw new AutonomyDeniedException("Autonomy is paused, so nothing is sent to your devices "
          + "until you release the kill switch.");
    }
    if (req.action() == null || req.action().isBlank()) {
      throw new ValidationException("Say which device action to run");
    }
    DeviceActionSpec newest = catalog.resolve(req.action(), 0).orElseThrow(() ->
        new ValidationException("'" + req.action() + "' is not a device action Jizza knows"));
    DeviceEntity device = route(req, newest);

    int declared = declaredVersion(device, req.action());
    int version = req.version() > 0 ? req.version()
        : Math.max(1, Math.min(declared == 0 ? newest.version() : declared, newest.version()));
    DeviceActionSpec spec = newest.extension()
        // A tool the device described: its own schema and time limit, still judged as the riskiest kind.
        ? DeviceExtras.spec(device, req.action())
            .orElseGet(() -> DeviceActionSpec.unknownExtension(req.action(), version))
        : catalog.resolve(req.action(), version).orElseThrow(() ->
            new ValidationException("Version " + version + " of " + req.action() + " does not exist"));
    if (!implicit(spec) && declared != 0 && declared < version) {
      throw new ValidationException(device.getName() + " runs version " + declared + " of "
          + spec.action() + "; update its Jizza Agent app for version " + version);
    }

    JsonNode input = req.input() == null || req.input().isNull() ? Json.obj() : req.input();
    if (spec.input() != null) {
      ToolSchemaValidator.Result valid = ToolSchemaValidator.validate(spec.input(), input);
      if (!valid.valid()) {
        throw new ValidationException("The inputs for " + spec.title() + " are not right: "
            + valid.message());
      }
    }
    if (spec.shell() && !device.isShellEnabled()) {
      throw new ValidationException("Shell commands are turned off for " + device.getName()
          + ". Turn them on in the device's settings first.");
    }
    if (blocked(device, spec.action())) {
      throw new ValidationException(spec.title() + " is turned off for " + device.getName());
    }
    Optional<String> limit = LocalLimits.check(spec,
        device.getManifest() == null ? null : device.getManifest().path("capabilities"), input);
    if (limit.isPresent()) {
      throw new ValidationException(device.getName() + " would refuse this: " + limit.get());
    }
    if (commands.countByDeviceIdAndStatusIn(device.getId(), OPEN) >= props.getMaxQueuedPerDevice()) {
      throw new RateLimitedException(device.getName() + " already has "
          + props.getMaxQueuedPerDevice() + " commands waiting");
    }
    if (commands.countByWorkspaceIdAndCreatedAtAfter(device.getWorkspaceId(),
        clock.instant().minusSeconds(60)) >= props.getMaxCommandsPerMinutePerWorkspace()) {
      throw new RateLimitedException("Too many device commands this minute; try again shortly");
    }

    Duration timeout = req.timeout() != null ? req.timeout()
        : Duration.ofSeconds(spec.defaultTimeoutSec());
    if (timeout.isNegative() || timeout.isZero()) timeout = Duration.ofSeconds(spec.defaultTimeoutSec());
    if (timeout.compareTo(props.getMaxCommandTimeout()) > 0) timeout = props.getMaxCommandTimeout();

    // Millisecond precision, so the signed text rebuilt from the row later is the same text.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    DeviceCommandEntity cmd = new DeviceCommandEntity();
    cmd.setDeviceId(device.getId());
    cmd.setUserId(req.userId());
    cmd.setWorkspaceId(device.getWorkspaceId());
    cmd.setRunId(req.runId());
    cmd.setNodeId(req.nodeId());
    cmd.setAction(spec.action());
    cmd.setActionVersion(spec.version());
    cmd.setInput(encryption.encryptString(Json.write(input)));
    cmd.setStatus("queued");
    cmd.setInteractive(req.interactive());
    cmd.setIdempotencyKey(req.idempotencyKey());
    cmd.setNonce(MessageIds.next());
    cmd.setCreatedAt(now);
    cmd.setDeadlineAt(now.plus(timeout));
    // Saved first for its id, which the signed body carries.
    commands.saveAndFlush(cmd);
    cmd.setSignature(serverKeys.sign(Canonical.of(body(cmd, input))));
    commands.saveAndFlush(cmd);

    audit.record(req.userId(), ActorType.SYSTEM, null, "device.command_sent", device.getName(),
        Json.obj().put("deviceId", device.getId().toString()).put("commandId", cmd.getId().toString())
            .put("action", spec.ref()).put("runId", String.valueOf(req.runId())));
    UUID deviceId = device.getId();
    boolean phone = DevicePlatform.from(device.getPlatform()).phone();
    UUID commandId = cmd.getId();
    afterCommit(() -> dispatch(deviceId, commandId, phone));
    return toDto(cmd);
  }

  /** The devices this person may use that can run the action, best first. */
  @Override
  @Transactional(readOnly = true)
  public Optional<DeviceActionSpec> describe(DeviceCommandRequest req) {
    if (req.action() == null || req.action().isBlank()) return Optional.empty();
    Optional<DeviceActionSpec> known = catalog.resolve(req.action(), req.version());
    if (known.isEmpty() || !known.get().extension()) return known;
    DeviceActionSpec unknown = known.get();
    List<DeviceEntity> candidates;
    try {
      DeviceEntity routed = route(req, unknown);
      candidates = req.target() != null && req.target().deviceId() != null ? List.of(routed)
          // Another of them may take it by the time it is sent, so judge by the riskiest.
          : (req.workspaceId() == null
              ? devices.findByOwnerUserIdAndStatusOrderByCreatedAtAsc(req.userId(), "active")
              : devices.findByWorkspaceIdOrderByCreatedAtAsc(req.workspaceId())).stream()
              .filter(d -> "active".equals(d.getStatus()) && canRun(d, unknown))
              .filter(d -> d.getOwnerUserId() == null || d.getOwnerUserId().equals(req.userId()))
              .toList();
    } catch (ValidationException | NotFoundException e) {
      // Nothing would take it; submit says why.
      return known;
    }
    DeviceActionSpec chosen = null;
    for (DeviceEntity d : candidates) {
      DeviceActionSpec own = DeviceExtras.spec(d, req.action()).orElse(unknown);
      if (chosen == null || ActionType.riskier(chosen.actionType(), own.actionType()) != chosen.actionType()) {
        chosen = own;
      }
    }
    return Optional.of(chosen == null ? unknown : chosen);
  }

  private DeviceEntity route(DeviceCommandRequest req, DeviceActionSpec spec) {
    DeviceTarget target = req.target() == null ? DeviceTarget.any() : req.target();
    // A workflow run belongs to a person, not an account, so without one it uses their own devices.
    List<DeviceEntity> pool = req.workspaceId() == null
        ? devices.findByOwnerUserIdAndStatusOrderByCreatedAtAsc(req.userId(), "active")
        : devices.findByWorkspaceIdOrderByCreatedAtAsc(req.workspaceId());
    List<DeviceEntity> usable = pool.stream()
        .filter(d -> "active".equals(d.getStatus()))
        .filter(d -> d.getOwnerUserId() == null || d.getOwnerUserId().equals(req.userId()))
        .toList();
    if (target.deviceId() != null) {
      DeviceEntity device = devices.findById(target.deviceId())
          .filter(d -> req.workspaceId() == null ? req.userId().equals(d.getOwnerUserId())
              : d.getWorkspaceId().equals(req.workspaceId())
                  && (d.getOwnerUserId() == null || d.getOwnerUserId().equals(req.userId())))
          .orElseThrow(() -> new NotFoundException("Device", target.deviceId()));
      if (!"active".equals(device.getStatus())) {
        throw new ValidationException(device.getName() + " is " + device.getStatus());
      }
      if (!canRun(device, spec)) {
        throw new ValidationException(device.getName() + " cannot " + lower(spec.title())
            + ". Its Jizza Agent app did not offer that action.");
      }
      return device;
    }
    if (usable.isEmpty()) {
      throw new ValidationException("You have no paired devices. Pair one under Settings > Devices.");
    }
    List<DeviceEntity> matching = usable.stream()
        .filter(d -> target.tag() == null || hasTag(d, target.tag()))
        .filter(d -> target.capability() == null || hasCapability(d, target.capability()))
        .filter(d -> !target.phone() || DevicePlatform.from(d.getPlatform()).phone())
        .filter(d -> canRun(d, spec))
        .sorted(Comparator.comparingInt(this::rank)
            .thenComparing(d -> d.getLastSeenAt() == null ? Instant.EPOCH : d.getLastSeenAt(),
                Comparator.reverseOrder()))
        .toList();
    if (matching.isEmpty()) {
      throw new ValidationException("None of " + target.describe().replace("any of your devices",
          "your devices") + " can " + lower(spec.title()) + " yet.");
    }
    return matching.get(0);
  }

  private int rank(DeviceEntity d) {
    boolean online = hub.online(d.getId());
    if (online && "idle".equals(hub.state(d.getId()))) return 0;
    if (online) return 1;
    if (DevicePlatform.from(d.getPlatform()).phone()) return 2;
    return 3;
  }

  private static boolean canRun(DeviceEntity d, DeviceActionSpec spec) {
    return implicit(spec) || declaredVersion(d, spec.action()) > 0;
  }

  /** Every app answers a ping, even before it has sent a manifest. */
  private static boolean implicit(DeviceActionSpec spec) {
    return "system.ping".equals(spec.action());
  }

  static int declaredVersion(DeviceEntity d, String action) {
    if (d.getActions() == null) return 0;
    for (JsonNode a : d.getActions()) {
      String ref = a.asText();
      if (ref.equals(action)) return 1;
      if (ref.startsWith(action + "@")) {
        try {
          return Integer.parseInt(ref.substring(action.length() + 1));
        } catch (NumberFormatException e) {
          return 0;
        }
      }
    }
    return 0;
  }

  private static boolean hasTag(DeviceEntity d, String tag) {
    if (d.getTags() == null) return false;
    for (JsonNode t : d.getTags()) if (t.asText().equalsIgnoreCase(tag)) return true;
    return false;
  }

  private static boolean hasCapability(DeviceEntity d, String capability) {
    return d.getManifest() != null && d.getManifest().path("capabilities").has(capability);
  }

  private static boolean blocked(DeviceEntity d, String action) {
    if (d.getBlockedActions() == null) return false;
    for (JsonNode a : d.getBlockedActions()) if (a.asText().equals(action)) return true;
    return false;
  }

  // ── Delivering ───────────────────────────────────────────────────────────────

  /** Sends one committed command if its device has a socket open; wakes a phone otherwise. */
  void dispatch(UUID deviceId, UUID commandId, boolean phone) {
    if (hub.socketOpen(deviceId)) {
      sendPending(deviceId);
      return;
    }
    hub.signal(deviceId);
    if (phone) {
      push.wake(deviceId, Json.obj().put("type", "work").put("commandId", commandId.toString()));
    }
  }

  /**
   * Sends a connected device everything not yet accepted: new commands, and ones sent before that
   * it never confirmed (it may have missed them). The device ignores an id it has already run.
   */
  public void sendPending(UUID deviceId) {
    List<ObjectNode> envelopes = transactions.execute(status -> {
      List<ObjectNode> out = new ArrayList<>();
      for (DeviceCommandEntity cmd : commands
          .findByDeviceIdAndStatusInOrderByInteractiveDescCreatedAtAsc(deviceId, NOT_ACCEPTED)) {
        out.add(envelope(cmd));
        if ("queued".equals(cmd.getStatus())) {
          cmd.setStatus("dispatched");
          cmd.setDispatchedAt(clock.instant());
          commands.save(cmd);
        }
      }
      return out;
    });
    if (envelopes == null) return;
    for (ObjectNode env : envelopes) hub.deliver(deviceId, env);
  }

  /** What a polling device gets: new commands, and dispatched ones it has not confirmed lately. */
  @Transactional
  public List<ObjectNode> takeForPoll(UUID deviceId) {
    List<ObjectNode> out = new ArrayList<>();
    Instant reoffer = clock.instant().minus(REOFFER_AFTER);
    for (DeviceCommandEntity cmd : commands
        .findByDeviceIdAndStatusInOrderByInteractiveDescCreatedAtAsc(deviceId, NOT_ACCEPTED)) {
      if ("dispatched".equals(cmd.getStatus()) && cmd.getDispatchedAt() != null
          && cmd.getDispatchedAt().isAfter(reoffer)) {
        continue;
      }
      out.add(envelope(cmd));
      cmd.setStatus("dispatched");
      cmd.setDispatchedAt(clock.instant());
      commands.save(cmd);
    }
    return out;
  }

  public boolean hasWork(UUID deviceId) {
    return commands.countByDeviceIdAndStatusIn(deviceId, Set.of("queued")) > 0;
  }

  private ObjectNode envelope(DeviceCommandEntity cmd) {
    JsonNode input = Json.read(encryption.decryptString(cmd.getInput()));
    ObjectNode body = body(cmd, input);
    // Files a command hands the device are named in its input; nothing else rides along.
    ObjectNode env = Protocol.envelope(Protocol.COMMAND, cmd.getDeviceId(), body, null);
    env.put("sig", cmd.getSignature());
    return env;
  }

  /** The signed part of a command: everything the device needs to check it is genuine and fresh. */
  static ObjectNode body(DeviceCommandEntity cmd, JsonNode input) {
    ObjectNode body = Json.obj();
    body.put("commandId", cmd.getId().toString());
    body.put("deviceId", cmd.getDeviceId().toString());
    body.put("action", cmd.getAction());
    body.put("version", cmd.getActionVersion());
    body.set("input", input);
    body.put("issuedAt", cmd.getCreatedAt().toString());
    body.put("deadline", cmd.getDeadlineAt().toString());
    body.put("nonce", cmd.getNonce());
    if (cmd.getIdempotencyKey() == null) body.putNull("idempotencyKey");
    else body.put("idempotencyKey", cmd.getIdempotencyKey());
    return body;
  }

  // ── What devices report ──────────────────────────────────────────────────────

  @Transactional
  public void accepted(UUID deviceId, JsonNode body) {
    DeviceCommandEntity cmd = owned(deviceId, body);
    if (cmd == null || !NOT_ACCEPTED.contains(cmd.getStatus())) return;
    cmd.setStatus("accepted");
    cmd.setAcceptedAt(clock.instant());
    if (cmd.getDispatchedAt() == null) cmd.setDispatchedAt(cmd.getAcceptedAt());
    commands.save(cmd);
    publish(cmd);
  }

  @Transactional
  public void rejected(UUID deviceId, JsonNode body) {
    DeviceCommandEntity cmd = owned(deviceId, body);
    if (cmd == null || DeviceCommandStatus.valueOf(cmd.getStatus().toUpperCase(Locale.ROOT)).finished()) {
      return;
    }
    finish(cmd, "rejected", body.path("code").asText(Protocol.Errors.POLICY_DENIED),
        limit(body.path("message").asText(null), 1000), null, false);
  }

  @Transactional
  public void progress(UUID deviceId, JsonNode body) {
    DeviceCommandEntity cmd = owned(deviceId, body);
    if (cmd == null || !OPEN.contains(cmd.getStatus())) return;
    if (cmd.getAcceptedAt() == null) cmd.setAcceptedAt(clock.instant());
    cmd.setStatus("running");
    String text = body.path("text").asText(null);
    if (body.hasNonNull("percent")) {
      text = body.get("percent").asInt() + "%" + (text == null ? "" : " · " + text);
    }
    cmd.setProgress(limit(text, MAX_PROGRESS));
    commands.save(cmd);
    publish(cmd);
  }

  /**
   * The command's outcome. Kept even when it does not match the action's declared output, flagged
   * so the run history can show it; a second result for the same command is ignored.
   */
  @Transactional
  public void result(UUID deviceId, JsonNode body) {
    DeviceCommandEntity cmd = owned(deviceId, body);
    if (cmd == null) {
      throw new NotFoundException("Command", body.path("commandId").asText());
    }
    if (DeviceCommandStatus.valueOf(cmd.getStatus().toUpperCase(Locale.ROOT)).finished()) return;
    boolean ok = "succeeded".equals(body.path("status").asText());
    JsonNode output = body.path("output").isMissingNode() ? Json.obj() : body.get("output");
    if (body.path("files").isArray() && output.isObject()) {
      // Only files this device uploaded for this command are passed on.
      var ids = Json.mapper().createArrayNode();
      for (JsonNode f : body.get("files")) {
        try {
          UUID id = UUID.fromString(f.asText());
          files.findById(id).filter(file -> deviceId.equals(file.getDeviceId())
              && cmd.getId().equals(file.getCommandId())).ifPresent(file -> ids.add(id.toString()));
        } catch (IllegalArgumentException ignored) {
          // Not an id; dropped.
        }
      }
      ((ObjectNode) output).set("_files", ids);
    }
    boolean mismatch = false;
    if (ok) {
      DeviceActionSpec spec = catalog.find(cmd.getAction(), cmd.getActionVersion()).orElse(null);
      if (spec != null && spec.output() != null) {
        mismatch = !ToolSchemaValidator.validate(spec.output(), output).valid();
      }
    }
    JsonNode error = body.path("error");
    finish(cmd, ok ? "succeeded" : "failed",
        ok ? null : error.path("code").asText(Protocol.Errors.FAILED),
        ok ? null : limit(error.path("message").asText(error.isTextual() ? error.asText() : null), 2000),
        output, mismatch);
  }

  /**
   * The device needs a person (a login, a captcha, an OS permission). The person is told, and the
   * command gets extra time so the run does not fail while they help.
   */
  @Transactional
  public void needsUser(UUID deviceId, String deviceName, JsonNode body) {
    DeviceCommandEntity cmd = owned(deviceId, body);
    String message = limit(body.path("message").asText("Your device needs you to continue."), 500);
    if (cmd != null && OPEN.contains(cmd.getStatus())) {
      Instant extended = clock.instant().plus(props.getNeedsUserGrace());
      if (extended.isAfter(cmd.getDeadlineAt())) cmd.setDeadlineAt(extended);
      cmd.setProgress(limit("Waiting for you: " + message, MAX_PROGRESS));
      commands.save(cmd);
      publish(cmd);
    }
    UUID userId = cmd != null ? cmd.getUserId()
        : devices.findById(deviceId).map(d -> d.getOwnerUserId()).orElse(null);
    NotificationBus bus = notifications.getIfAvailable();
    if (bus != null && userId != null) {
      ObjectNode detail = Json.obj().put("deviceId", deviceId.toString())
          .put("source", "device").put("kind", body.path("kind").asText("other"));
      if (cmd != null) detail.put("commandId", cmd.getId().toString());
      if (body.path("steps").isArray()) detail.set("steps", body.get("steps"));
      bus.raise(NotificationKind.INFO, null, new NotificationContext(userId,
          "On " + deviceName + ": " + message, detail, null, null,
          cmd == null ? null : cmd.getRunId(), null, NotificationPriority.URGENT));
    }
  }

  private DeviceCommandEntity owned(UUID deviceId, JsonNode body) {
    String id = body.path("commandId").asText(null);
    if (id == null) throw new ValidationException("The message does not name its command");
    UUID commandId;
    try {
      commandId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("'" + id + "' is not a command id");
    }
    // A device can only speak about its own commands.
    return commands.findById(commandId).filter(c -> c.getDeviceId().equals(deviceId)).orElse(null);
  }

  // ── Stopping and expiring ────────────────────────────────────────────────────

  @Override
  @Transactional
  public void cancel(UUID commandId, String reason) {
    commands.findById(commandId).ifPresent(cmd -> cancel(cmd, reason));
  }

  @Override
  @Transactional
  public void cancelForRun(UUID runId, String reason) {
    commands.findByRunIdAndStatusIn(runId, OPEN).forEach(cmd -> cancel(cmd, reason));
  }

  @Transactional
  public void cancelForDevice(UUID deviceId, String reason) {
    commands.findByDeviceIdAndStatusInOrderByInteractiveDescCreatedAtAsc(deviceId, OPEN)
        .forEach(cmd -> cancel(cmd, reason));
  }

  /** The kill switch: everything unfinished for this person stops now. */
  @Transactional
  public int cancelForUser(UUID userId, String reason) {
    List<DeviceCommandEntity> open = commands.findByUserIdAndStatusIn(userId, OPEN);
    open.forEach(cmd -> cancel(cmd, reason));
    return open.size();
  }

  private void cancel(DeviceCommandEntity cmd, String reason) {
    if (!OPEN.contains(cmd.getStatus())) return;
    boolean onDevice = !"queued".equals(cmd.getStatus());
    finish(cmd, "cancelled", Protocol.Errors.CANCELLED, reason, null, false);
    if (onDevice) sendCancel(cmd, reason);
  }

  /** Expires commands past their deadline; called by the sweeper. */
  @Transactional
  public int expireOverdue() {
    List<DeviceCommandEntity> overdue = commands.findOverdue(OPEN, clock.instant());
    for (DeviceCommandEntity cmd : overdue) {
      boolean neverTaken = cmd.getAcceptedAt() == null;
      boolean onDevice = !"queued".equals(cmd.getStatus());
      finish(cmd, "expired", neverTaken ? Protocol.Errors.DEVICE_OFFLINE : Protocol.Errors.TIMED_OUT,
          neverTaken ? "The device did not pick it up in time" : "The device did not finish in time",
          null, false);
      if (onDevice) sendCancel(cmd, "time limit reached");
    }
    return overdue.size();
  }

  private void sendCancel(DeviceCommandEntity cmd, String reason) {
    ObjectNode body = Json.obj().put("commandId", cmd.getId().toString())
        .put("deviceId", cmd.getDeviceId().toString()).put("reason", String.valueOf(reason))
        .put("issuedAt", clock.instant().toString());
    ObjectNode env = Protocol.envelope(Protocol.CANCEL, cmd.getDeviceId(), body, null);
    env.put("sig", serverKeys.sign(Canonical.of(body)));
    UUID deviceId = cmd.getDeviceId();
    afterCommit(() -> hub.deliver(deviceId, env));
  }

  private void finish(DeviceCommandEntity cmd, String status, String code, String error,
                      JsonNode output, boolean mismatch) {
    cmd.setStatus(status);
    cmd.setErrorCode(code);
    cmd.setError(error);
    if (output != null) cmd.setOutput(encryption.encryptString(Json.write(output)));
    cmd.setOutputMismatch(mismatch);
    cmd.setFinishedAt(clock.instant());
    commands.save(cmd);
    audit.record(cmd.getUserId(), ActorType.SYSTEM, null, "device.command_finished",
        cmd.getAction(), Json.obj().put("commandId", cmd.getId().toString())
            .put("deviceId", cmd.getDeviceId().toString()).put("status", status)
            .put("code", String.valueOf(code)));
    DeviceCommandDto dto = toDto(cmd);
    afterCommit(() -> {
      publishDto(dto);
      CompletableFuture<Void> future = waiting.remove(dto.id());
      if (future != null) future.complete(null);
      listeners.orderedStream().forEach(listener -> {
        try {
          listener.onFinished(dto);
        } catch (RuntimeException e) {
          log.warn("A listener failed on command {}", dto.id(), e);
        }
      });
    });
  }

  // ── Reading ──────────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public Optional<DeviceCommandDto> find(UUID commandId) {
    return commands.findById(commandId).map(this::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<DeviceCommandDto> recent(UUID deviceId, int limit) {
    return commands.findByDeviceIdOrderByCreatedAtDesc(deviceId,
        PageRequest.of(0, Math.max(1, Math.min(limit, 200)))).stream().map(this::toDto).toList();
  }

  @Override
  public DeviceCommandDto await(UUID commandId, Duration wait) {
    CompletableFuture<Void> future = waiting.computeIfAbsent(commandId, id -> new CompletableFuture<>());
    DeviceCommandDto now = find(commandId).orElseThrow(() -> new NotFoundException("Command", commandId));
    if (now.status().finished()) {
      waiting.remove(commandId);
      return now;
    }
    try {
      future.get(Math.max(1, wait.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      // Still running; the caller says so.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      waiting.remove(commandId, future);
    }
    return find(commandId).orElse(now);
  }

  public DeviceCommandDto toDto(DeviceCommandEntity c) {
    String name = devices.findById(c.getDeviceId()).map(DeviceEntity::getName).orElse("the device");
    List<UUID> fileIds = files.findByCommandIdOrderByCreatedAtAsc(c.getId()).stream()
        .map(DeviceFileEntity::getId).toList();
    return new DeviceCommandDto(c.getId(), c.getDeviceId(), name, c.getUserId(), c.getRunId(),
        c.getNodeId(), c.getAction(), c.getActionVersion(),
        c.getInput() == null ? null : Json.read(encryption.decryptString(c.getInput())),
        DeviceCommandStatus.valueOf(c.getStatus().toUpperCase(Locale.ROOT)),
        c.getOutput() == null ? null : Json.read(encryption.decryptString(c.getOutput())),
        c.getErrorCode(), c.getError(), c.getProgress(), fileIds, c.getDeadlineAt(),
        c.getCreatedAt(), c.getDispatchedAt(), c.getAcceptedAt(), c.getFinishedAt());
  }

  private void publish(DeviceCommandEntity cmd) {
    DeviceCommandDto dto = toDto(cmd);
    afterCommit(() -> publishDto(dto));
  }

  /** Live progress for the run view and the device page. Inputs and outputs stay out of it. */
  private void publishDto(DeviceCommandDto dto) {
    RealtimeGateway gateway = realtime.getIfAvailable();
    if (gateway == null) return;
    try {
      gateway.publish(dto.userId(), "device-commands", Map.of(
          "id", dto.id().toString(), "deviceId", dto.deviceId().toString(),
          "runId", String.valueOf(dto.runId()), "action", dto.action(),
          "status", dto.status().name(), "progress", Objects.requireNonNullElse(dto.progress(), "")));
    } catch (RuntimeException e) {
      log.debug("Could not publish command progress", e);
    }
  }

  static void afterCommit(Runnable task) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          task.run();
        }
      });
    } else {
      task.run();
    }
  }

  private static String limit(String value, int max) {
    if (value == null) return null;
    return value.length() > max ? value.substring(0, max) : value;
  }

  private static String lower(String title) {
    return title.isEmpty() ? title : Character.toLowerCase(title.charAt(0)) + title.substring(1);
  }
}
