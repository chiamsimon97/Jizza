package com.jizza.engine.devices.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.device.DeviceActionCatalog;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceAgentGateway;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.notification.Answer;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.storage.StorageProvider;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import com.jizza.engine.devices.broker.CommandBroker;
import com.jizza.engine.devices.config.DeviceProperties;
import com.jizza.engine.devices.events.DeviceEventIntake;
import com.jizza.engine.devices.protocol.Protocol;
import com.jizza.engine.devices.registry.DeviceRegistryService;
import com.jizza.engine.persistence.entity.DeviceCommandEntity;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.entity.DeviceFileEntity;
import com.jizza.engine.persistence.entity.DeviceLogEntity;
import com.jizza.engine.persistence.repository.DeviceCommandRepository;
import com.jizza.engine.persistence.repository.DeviceFileRepository;
import com.jizza.engine.persistence.repository.DeviceLogRepository;
import com.jizza.engine.persistence.repository.DeviceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The server side of the Jizza Agent Protocol (see {@link DeviceAgentGateway}). The socket handler
 * and the HTTPS routes both come through {@link #receive}, so a message means the same thing
 * however it travelled.
 */
@Service
public class AgentGatewayService implements DeviceAgentGateway {

  private static final int MAX_LOG_LINES = 100;
  private static final Set<String> STATES = Set.of("idle", "busy", "paused");

  private final DeviceRegistryService registry;
  private final CommandBroker broker;
  private final DeviceEventIntake events;
  private final DeviceHub hub;
  private final DeviceActionCatalog catalog;
  private final DeviceRepository devices;
  private final DeviceCommandRepository commands;
  private final DeviceFileRepository files;
  private final DeviceLogRepository logs;
  private final StorageProvider storage;
  private final EncryptionService encryption;
  private final ObjectProvider<NotificationBus> notifications;
  private final DeviceProperties props;

  public AgentGatewayService(DeviceRegistryService registry, CommandBroker broker,
                             DeviceEventIntake events, DeviceHub hub, DeviceActionCatalog catalog,
                             DeviceRepository devices, DeviceCommandRepository commands,
                             DeviceFileRepository files, DeviceLogRepository logs,
                             StorageProvider storage, EncryptionService encryption,
                             ObjectProvider<NotificationBus> notifications, DeviceProperties props) {
    this.registry = registry;
    this.broker = broker;
    this.events = events;
    this.hub = hub;
    this.catalog = catalog;
    this.devices = devices;
    this.commands = commands;
    this.files = files;
    this.logs = logs;
    this.storage = storage;
    this.encryption = encryption;
    this.notifications = notifications;
    this.props = props;
  }

  @Override
  public Enrolled enroll(Enrollment enrollment, String remoteAddress) {
    return registry.enroll(enrollment, remoteAddress);
  }

  @Override
  public Challenge challenge(UUID deviceId) {
    return registry.challenge(deviceId);
  }

  @Override
  public AccessToken token(UUID deviceId, String nonce, String signature, String remoteAddress) {
    return registry.token(deviceId, nonce, signature, remoteAddress);
  }

  @Override
  public Caller authenticate(String bearerToken) {
    return registry.authenticate(bearerToken);
  }

  @Override
  public JsonNode receive(Caller caller, JsonNode envelope) {
    String replyTo = envelope != null && envelope.path("id").isTextual()
        ? envelope.get("id").asText() : null;
    if (envelope == null || !envelope.isObject()) {
      return Protocol.error(caller.deviceId(), null, Protocol.Errors.BAD_MESSAGE,
          "A message is one JSON object");
    }
    if (envelope.path("v").asInt(0) != 1) {
      return Protocol.error(caller.deviceId(), replyTo, Protocol.Errors.UNSUPPORTED_VERSION,
          "This server speaks protocol version 1");
    }
    String type = envelope.path("type").asText("");
    if (!Protocol.FROM_DEVICE.contains(type)) {
      return Protocol.error(caller.deviceId(), replyTo, Protocol.Errors.UNSUPPORTED_TYPE,
          "'" + type + "' is not a message a device sends");
    }
    if (replyTo == null || replyTo.length() > 64) {
      return Protocol.error(caller.deviceId(), null, Protocol.Errors.BAD_MESSAGE,
          "Every message needs an id of up to 64 characters");
    }
    if (envelope.hasNonNull("deviceId")
        && !caller.deviceId().toString().equals(envelope.get("deviceId").asText())) {
      return Protocol.error(caller.deviceId(), replyTo, Protocol.Errors.UNAUTHORIZED,
          "A device can only speak for itself");
    }
    JsonNode body = envelope.path("body").isObject() ? envelope.get("body") : Json.obj();
    hub.touch(caller.deviceId(), null);
    try {
      return handle(caller, type, body, replyTo);
    } catch (AuthException e) {
      return Protocol.error(caller.deviceId(), replyTo, Protocol.Errors.UNAUTHORIZED, e.getMessage());
    } catch (NotFoundException e) {
      return Protocol.error(caller.deviceId(), replyTo, Protocol.Errors.NOT_FOUND, e.getMessage());
    } catch (JizzaException | IllegalArgumentException e) {
      return Protocol.error(caller.deviceId(), replyTo, Protocol.Errors.BAD_MESSAGE, e.getMessage());
    }
  }

  private JsonNode handle(Caller caller, String type, JsonNode body, String replyTo) {
    UUID deviceId = caller.deviceId();
    switch (type) {
      case Protocol.HELLO -> {
        registry.recordHello(deviceId, body);
        DeviceEntity device = devices.findById(deviceId).orElseThrow();
        ObjectNode welcome = Json.obj();
        welcome.put("protocol", device.getProtocolVersion() == null ? props.currentProtocol()
            : device.getProtocolVersion());
        welcome.put("sessionId", UUID.randomUUID().toString());
        welcome.put("heartbeatSec", props.getHeartbeat().toSeconds());
        welcome.put("pollWaitSec", props.getMaxPollWait().toSeconds());
        welcome.put("serverTime", Instant.now().toString());
        welcome.put("pendingCommands", commands.countByDeviceIdAndStatusIn(deviceId,
            Set.of("queued", "dispatched")));
        welcome.put("shellEnabled", device.isShellEnabled());
        welcome.set("blockedActions", device.getBlockedActions());
        int[] supported = props.getProtocolVersions();
        var versions = welcome.putArray("supportedProtocols");
        for (int v : supported) versions.add(v);
        return Protocol.envelope(Protocol.WELCOME, deviceId, welcome, replyTo);
      }
      case Protocol.MANIFEST -> {
        registry.recordManifest(deviceId, body);
        return Protocol.ack(deviceId, replyTo);
      }
      case Protocol.HEARTBEAT -> {
        String state = body.path("state").asText("idle").toLowerCase(Locale.ROOT);
        hub.touch(deviceId, STATES.contains(state) ? state : "idle");
        return null;
      }
      case Protocol.ACCEPTED -> {
        broker.accepted(deviceId, body);
        return null;
      }
      case Protocol.REJECTED -> {
        broker.rejected(deviceId, body);
        return null;
      }
      case Protocol.PROGRESS -> {
        broker.progress(deviceId, body);
        return null;
      }
      case Protocol.RESULT -> {
        broker.result(deviceId, body);
        return Protocol.ack(deviceId, replyTo);
      }
      case Protocol.EVENT -> {
        events.accept(deviceId, caller.userId(), body);
        return Protocol.ack(deviceId, replyTo);
      }
      case Protocol.NEEDS_USER -> {
        String name = devices.findById(deviceId).map(DeviceEntity::getName).orElse("your device");
        broker.needsUser(deviceId, name, body);
        return Protocol.ack(deviceId, replyTo);
      }
      case Protocol.ANSWER -> {
        answer(caller, body);
        return Protocol.ack(deviceId, replyTo);
      }
      case Protocol.LOG -> {
        log(deviceId, body);
        return null;
      }
      default -> {
        // bye and ack need nothing from the server.
        return null;
      }
    }
  }

  /**
   * An answer to an inbox item, from a phone. Only the device's own person can answer, and an
   * approval must come with the phone's biometric check, as the web app asks for a fresh sign-in.
   */
  private void answer(Caller caller, JsonNode body) {
    DeviceEntity device = devices.findById(caller.deviceId()).orElseThrow();
    if (device.getOwnerUserId() == null) {
      throw new AuthException("A shared device cannot answer for a person");
    }
    UUID notificationId = UUID.fromString(body.path("notificationId").asText());
    NotificationBus bus = notifications.getIfAvailable();
    if (bus == null) throw new ValidationException("The inbox is not available here");
    NotificationDto item = bus.get(notificationId);
    if (item == null || !device.getOwnerUserId().equals(item.userId())) {
      throw new NotFoundException("Notification", notificationId);
    }
    String decision = body.path("decision").asText("");
    Answer answer = switch (decision) {
      case "approve" -> {
        if (!body.path("biometric").asBoolean(false)) {
          throw new AuthException("Approving from a device needs its fingerprint or face check");
        }
        yield Answer.approve();
      }
      case "deny" -> Answer.deny(body.path("feedback").asText(null));
      case "instruction" -> Answer.instruction(body.path("instruction").asText(""));
      default -> throw new ValidationException("decision must be approve, deny or instruction");
    };
    bus.answer(notificationId, answer);
  }

  void log(UUID deviceId, JsonNode body) {
    JsonNode lines = body.path("lines");
    if (!lines.isArray()) return;
    int count = 0;
    for (JsonNode line : lines) {
      if (count++ >= MAX_LOG_LINES) break;
      DeviceLogEntity entity = new DeviceLogEntity();
      entity.setDeviceId(deviceId);
      entity.setTs(parseInstant(line.path("ts").asText(null)));
      String level = line.path("level").asText("info").toLowerCase(Locale.ROOT);
      entity.setLevel(Set.of("debug", "info", "warn", "error").contains(level) ? level : "info");
      String message = Redaction.scrub(line.path("message").asText(""));
      entity.setMessage(message.length() > 2000 ? message.substring(0, 2000) : message);
      try {
        if (line.hasNonNull("commandId")) entity.setCommandId(UUID.fromString(line.get("commandId").asText()));
      } catch (IllegalArgumentException ignored) {
        // Not an id; the line is kept without it.
      }
      logs.save(entity);
    }
  }

  @Override
  public List<JsonNode> poll(Caller caller, Duration wait) {
    Duration capped = wait == null || wait.isNegative() ? Duration.ZERO
        : wait.compareTo(props.getMaxPollWait()) > 0 ? props.getMaxPollWait() : wait;
    hub.touch(caller.deviceId(), null);
    List<JsonNode> out = collect(caller.deviceId());
    if (!out.isEmpty() || capped.isZero()) return out;
    Instant until = Instant.now().plus(capped);
    while (out.isEmpty() && Instant.now().isBefore(until)) {
      hub.await(caller.deviceId(), Duration.between(Instant.now(), until));
      out = collect(caller.deviceId());
    }
    hub.touch(caller.deviceId(), null);
    return out;
  }

  private List<JsonNode> collect(UUID deviceId) {
    List<JsonNode> out = new ArrayList<>(hub.drain(deviceId));
    out.addAll(broker.takeForPoll(deviceId));
    return out;
  }

  @Override
  @Transactional
  public UUID upload(Caller caller, UUID commandId, String name, String contentType, byte[] content) {
    if (content == null || content.length == 0) throw new ValidationException("The file is empty");
    if (content.length > props.getMaxFileBytes()) {
      throw new ValidationException("A file can be at most " + props.getMaxFileBytes() / (1024 * 1024) + " MB");
    }
    DeviceCommandEntity cmd = commands.findById(commandId)
        .filter(c -> c.getDeviceId().equals(caller.deviceId()))
        .orElseThrow(() -> new NotFoundException("Command", commandId));
    if (cmd.getFinishedAt() != null) {
      throw new ValidationException("That command has finished; upload files before its result");
    }
    String safeName = name == null || name.isBlank() ? "file" : name.replaceAll("[\\\\/\\r\\n]", "_");
    if (safeName.length() > 200) safeName = safeName.substring(0, 200);
    DeviceFileEntity file = new DeviceFileEntity();
    file.setDeviceId(caller.deviceId());
    file.setCommandId(commandId);
    file.setName(safeName);
    file.setContentType(contentType == null ? "application/octet-stream" : contentType);
    file.setSize(content.length);
    file.setStorageRef(storage.put(encryption.encrypt(content), "device-" + UUID.randomUUID()));
    files.save(file);
    return file.getId();
  }

  /**
   * A file a command handed this device, such as one another device downloaded and this one is to
   * save. Only files named in one of this device's unfinished commands can be fetched.
   */
  @Override
  @Transactional(readOnly = true)
  public Devices.DeviceFile download(Caller caller, UUID fileId) {
    DeviceFileEntity file = files.findById(fileId)
        .orElseThrow(() -> new NotFoundException("File", fileId));
    boolean own = caller.deviceId().equals(file.getDeviceId());
    boolean handedOver = commands.findByDeviceIdAndStatusInOrderByInteractiveDescCreatedAtAsc(
            caller.deviceId(), Set.of("dispatched", "accepted", "running")).stream()
        .anyMatch(c -> c.getInput() != null
            && encryption.decryptString(c.getInput()).contains(fileId.toString()));
    DeviceEntity owner = devices.findById(file.getDeviceId()).orElse(null);
    boolean sameWorkspace = owner != null && owner.getWorkspaceId().equals(caller.workspaceId());
    if (!own && !(handedOver && sameWorkspace)) throw new NotFoundException("File", fileId);
    return new Devices.DeviceFile(file.getId(), file.getDeviceId(), file.getCommandId(),
        file.getName(), file.getContentType(), file.getSize(),
        encryption.decrypt(storage.get(file.getStorageRef())));
  }

  @Override
  public void registerPushToken(Caller caller, String provider, String token) {
    registry.registerPushToken(caller.deviceId(), provider, token);
  }

  @Override
  public void rotateKey(Caller caller, String newPublicKey, String signature) {
    registry.rotateKey(caller.deviceId(), newPublicKey, signature);
  }

  @Override
  public List<DeviceActionSpec> catalog() {
    return catalog.all();
  }

  @Override
  public java.util.Optional<Owned> personalOwner(Caller caller) {
    return devices.findById(caller.deviceId())
        .filter(d -> d.getOwnerUserId() != null)
        .map(d -> new Owned(d.getOwnerUserId(), d.getName()));
  }

  @Override
  public boolean verifySignature(Caller caller, String text, String signature) {
    return registry.verifySignature(caller.deviceId(), text, signature);
  }

  private static Instant parseInstant(String text) {
    if (text == null) return Instant.now();
    try {
      Instant parsed = Instant.parse(text);
      return parsed.isAfter(Instant.now().plusSeconds(300)) ? Instant.now() : parsed;
    } catch (RuntimeException e) {
      return Instant.now();
    }
  }
}
