package com.jizza.engine.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.device.DeviceActionCatalog;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandRequest;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.DeviceTarget;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.RecordingIntake;
import com.jizza.engine.api.webauto.Recordings;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.persistence.entity.WebRecordingEntity;
import com.jizza.engine.persistence.entity.WebRecordingStepEntity;
import com.jizza.engine.persistence.repository.WebRecordingRepository;
import com.jizza.engine.persistence.repository.WebRecordingStepRepository;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps what a person does in their own browser while they record it, lets them review it, and
 * saves it as an ordinary workflow module.
 *
 * <p>Recordings come from a browser ({@code browser.recording.*}, started here) or from Windows apps
 * ({@code desktop.recording.*}, started on the PC with a hotkey, which this then follows with a
 * {@code ui.record} command so the person can stop it here too). Both are reviewed and saved the
 * same way.
 *
 * <p>Events arrive from a device and are trusted only when they come from the device the recording
 * was started on, for its owner, while it is still recording. Typed values can be sensitive, so
 * every step is encrypted at rest, a step marked sensitive never keeps a value, and nothing in
 * here logs step contents.
 */
@Service
public class RecordingService implements Recordings, RecordingIntake {

  private static final Logger log = LoggerFactory.getLogger(RecordingService.class);

  static final String TOPIC = "web-recordings";
  static final String STATUS_RECORDING = "recording";
  static final String STATUS_REVIEW = "review";
  static final String STATUS_SAVED = "saved";
  static final String STATUS_DISCARDED = "discarded";
  static final String STATUS_FAILED = "failed";

  private static final String RECORD_ACTION = "browser.record";
  static final String DESKTOP_RECORD_ACTION = "ui.record";
  static final String KIND_WEB = "web";
  static final String KIND_DESKTOP = "desktop";
  static final String WEB_EVENTS = "browser.recording.";
  static final String DESKTOP_EVENTS = "desktop.recording.";
  private static final int MAX_STEPS = 500;
  private static final int MAX_STEP_CHARS = 32 * 1024;
  private static final int MAX_SITES = 20;
  private static final Duration EVENT_GRACE = Duration.ofSeconds(3);
  private static final Duration RECORD_TIMEOUT = Duration.ofMinutes(31);
  private static final Set<String> TYPES =
      Set.of("text", "number", "boolean", "date", "list", "map", "any");
  private static final Set<String> VALUE_FIELDS = Set.of("text", "value", "password", "secret");
  private static final Set<String> CHECKS = Set.of("browser.exists", "browser.getText");
  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

  private final WebRecordingRepository recordings;
  private final WebRecordingStepRepository steps;
  private final Devices devices;
  private final DeviceCommands commands;
  private final DeviceActionCatalog catalog;
  private final EncryptionService encryption;
  private final WorkflowRegistry workflows;
  private final ObjectProvider<WorkflowLibrary> library;
  private final ObjectProvider<RealtimeGateway> realtime;
  private final WebGuardrails guardrails;

  public RecordingService(WebRecordingRepository recordings, WebRecordingStepRepository steps,
                          Devices devices, DeviceCommands commands, DeviceActionCatalog catalog,
                          EncryptionService encryption, WorkflowRegistry workflows,
                          ObjectProvider<WorkflowLibrary> library,
                          ObjectProvider<RealtimeGateway> realtime, AgentDirectory agents,
                          AutonomyGate autonomy) {
    this.recordings = recordings;
    this.steps = steps;
    this.devices = devices;
    this.commands = commands;
    this.catalog = catalog;
    this.encryption = encryption;
    this.workflows = workflows;
    this.library = library;
    this.realtime = realtime;
    this.guardrails = new WebGuardrails(agents, autonomy);
  }

  // ---- person's side -----------------------------------------------------------------------

  @Override
  @Transactional
  public Recording start(Access access, UUID deviceId, String url, String name) {
    DeviceDto device = devices.list(access.workspaceId(), access.userId()).stream()
        .filter(candidate -> candidate.id().equals(deviceId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Browser", deviceId));
    String cleanUrl = webUrl(url);
    String cleanName = name == null ? "" : name.strip();
    if (cleanName.isEmpty() || cleanName.length() > 120) {
      throw new ValidationException("Give the recording a name of up to 120 characters");
    }
    DeviceActionSpec spec = catalog.resolve(RECORD_ACTION, 0)
        .filter(candidate -> "browser".equals(candidate.capability()))
        .orElseThrow(() -> new ValidationException("Recording is not available"));
    if (!device.declares(spec.action())) {
      throw new ValidationException(device.name() + " cannot record");
    }
    boolean busy = recordings.findByOwnerIdAndStatusInOrderByUpdatedAtDesc(
        access.userId(), List.of(STATUS_RECORDING)).stream()
        .anyMatch(r -> deviceId.equals(r.getDeviceId()));
    if (busy) throw new ConflictException(device.name() + " is already recording");

    WebRecordingEntity recording = new WebRecordingEntity();
    recording.setId(UUID.randomUUID());
    recording.setOwnerId(access.userId());
    recording.setWorkspaceId(access.workspaceId());
    recording.setDeviceId(deviceId);
    recording.setName(cleanName);
    recording.setStartUrl(cleanUrl);
    recording.setStatus(STATUS_RECORDING);
    recording.setSites(new String[] {host(cleanUrl)});
    recording.setStartedAt(Instant.now());
    recording.setUpdatedAt(Instant.now());
    recordings.saveAndFlush(recording);

    ObjectNode input = Json.obj().put("recordingId", recording.getId().toString())
        .put("url", cleanUrl).put("maxMinutes", 30);
    guardrails.judge(access, device, spec, input, "Recording");
    DeviceCommandDto sent = commands.submit(new DeviceCommandRequest(access.userId(),
        access.workspaceId(), DeviceTarget.device(deviceId), spec.action(), spec.version(), input,
        RECORD_TIMEOUT, null, null, "web-record:" + recording.getId(), true));
    recording.setCommandId(sent.id());
    recordings.save(recording);
    publish(recording, null);
    return view(recording, device.name(), null);
  }

  @Override
  @Transactional
  public List<Recording> list(Access access, Collection<String> statuses) {
    List<WebRecordingEntity> found = statuses == null || statuses.isEmpty()
        ? recordings.findByOwnerIdOrderByUpdatedAtDesc(access.userId())
        : recordings.findByOwnerIdAndStatusInOrderByUpdatedAtDesc(access.userId(), statuses);
    List<Recording> out = new ArrayList<>();
    for (WebRecordingEntity recording : found) {
      if (STATUS_DISCARDED.equals(recording.getStatus())) continue;
      reconcile(recording);
      out.add(view(recording, deviceName(recording), null));
    }
    return out;
  }

  @Override
  @Transactional
  public Recording get(Access access, UUID id) {
    WebRecordingEntity recording = owned(access, id);
    reconcile(recording);
    return view(recording, deviceName(recording), liveAndDeleted(id));
  }

  @Override
  @Transactional
  public Recording edit(Access access, UUID id, List<EditOp> ops) {
    WebRecordingEntity recording = recordings.lockById(id)
        .filter(r -> r.getOwnerId().equals(access.userId()))
        .orElseThrow(() -> new NotFoundException("Recording", id));
    if (!STATUS_REVIEW.equals(recording.getStatus())) {
      throw new ConflictException("Only a recording in review can be edited");
    }
    List<Held> held = load(id);
    for (EditOp op : ops == null ? List.<EditOp>of() : ops) apply(held, op, true);
    persist(recording, held);
    publish(recording, null);
    return view(recording, deviceName(recording), toViews(held));
  }

  @Override
  @Transactional
  public Recording stop(Access access, UUID id) {
    WebRecordingEntity recording = recordings.lockById(id)
        .filter(r -> r.getOwnerId().equals(access.userId()))
        .orElseThrow(() -> new NotFoundException("Recording", id));
    if (STATUS_RECORDING.equals(recording.getStatus())) {
      if (recording.getCommandId() != null) {
        commands.cancel(recording.getCommandId(), "The person stopped the recording");
      }
      toReview(recording);
    }
    return view(recording, deviceName(recording), liveAndDeleted(id));
  }

  @Override
  @Transactional
  public void discard(Access access, UUID id) {
    WebRecordingEntity recording = recordings.lockById(id)
        .filter(r -> r.getOwnerId().equals(access.userId()))
        .orElseThrow(() -> new NotFoundException("Recording", id));
    if (STATUS_SAVED.equals(recording.getStatus())) {
      recordings.delete(recording);
      return;
    }
    if (STATUS_RECORDING.equals(recording.getStatus()) && recording.getCommandId() != null) {
      commands.cancel(recording.getCommandId(), "The person discarded the recording");
    }
    recording.setStatus(STATUS_DISCARDED);
    recording.setUpdatedAt(Instant.now());
    recordings.save(recording);
    publish(recording, null);
  }

  @Override
  @Transactional
  public SaveResult save(Access access, UUID id, SaveRequest request) {
    WebRecordingEntity recording = recordings.lockById(id)
        .filter(r -> r.getOwnerId().equals(access.userId()))
        .orElseThrow(() -> new NotFoundException("Recording", id));
    if (!STATUS_REVIEW.equals(recording.getStatus())) {
      throw new ConflictException("Only a recording in review can be saved");
    }
    String name = request == null || request.name() == null || request.name().isBlank()
        ? recording.getName() : request.name().strip();
    if (name.length() > 120) throw new ValidationException("A name can be at most 120 characters");
    List<JsonNode> live = new ArrayList<>();
    for (Held h : load(id)) if (!h.entity.isDeleted()) live.add(h.node);
    if (live.isEmpty()) throw new ValidationException("There are no steps to save");

    ObjectNode graph = compile(recording, live, false);
    WorkflowDto saved = workflows.save(new WorkflowDto(null, access.userId(), name,
        request == null ? null : request.description(), graph, null, null,
        WorkflowOrigin.RECORDED, WorkflowStatus.DRAFT, null, 1, null, null));

    WorkflowLibrary lib = library.getIfAvailable();
    if (lib != null) lib.describe(access.userId(), saved.id(), reuseFor(request, graph, name));

    recording.setStatus(STATUS_SAVED);
    recording.setWorkflowId(saved.id());
    recording.setUpdatedAt(Instant.now());
    recordings.save(recording);
    steps.deleteAllFor(id);
    publish(recording, null);
    return new SaveResult(saved.id(), saved.version());
  }

  @Override
  @Transactional
  public JsonNode insertable(Access access, UUID id) {
    WebRecordingEntity recording = recordings.lockById(id)
        .filter(r -> r.getOwnerId().equals(access.userId()))
        .orElseThrow(() -> new NotFoundException("Recording", id));
    if (!STATUS_REVIEW.equals(recording.getStatus())) {
      throw new ConflictException("Only a recording in review can be inserted");
    }
    List<JsonNode> live = new ArrayList<>();
    for (Held h : load(id)) if (!h.entity.isDeleted()) live.add(h.node);
    if (live.isEmpty()) throw new ValidationException("There are no steps to insert");
    ObjectNode graph = compile(recording, live, true);
    recording.setStatus(STATUS_SAVED);
    recording.setUpdatedAt(Instant.now());
    recordings.save(recording);
    steps.deleteAllFor(id);
    publish(recording, null);
    return graph;
  }

  // ---- device's side -----------------------------------------------------------------------

  @Override
  @Transactional
  public void accept(UUID deviceId, UUID ownerId, String name, JsonNode data) {
    UUID recordingId = uuid(data == null ? null : data.path("recordingId").asText(null));
    String kind = name.startsWith(DESKTOP_EVENTS) ? KIND_DESKTOP : KIND_WEB;
    String event = name.substring((KIND_DESKTOP.equals(kind) ? DESKTOP_EVENTS : WEB_EVENTS).length());
    if (KIND_DESKTOP.equals(kind) && "started".equals(event)) {
      startedOnDevice(deviceId, ownerId, recordingId, data);
      return;
    }
    WebRecordingEntity recording = recordingId == null ? null
        : recordings.lockById(recordingId).orElse(null);
    if (recording == null || !recording.getOwnerId().equals(ownerId)
        || !deviceId.equals(recording.getDeviceId())
        || !kind.equals(recording.getKind())
        || !(STATUS_RECORDING.equals(recording.getStatus()) || lateEvent(recording, event))) {
      log.info("Dropped {} for recording {}: not from its device and owner, or not recording",
          name, recordingId);
      return;
    }
    switch (event) {
      case "step" -> addStep(recording, data.path("step"));
      case "edit" -> deviceEdit(recording, data);
      case "stopped" -> {
        if (STATUS_RECORDING.equals(recording.getStatus())) toReview(recording);
      }
      default -> log.info("Dropped unknown recording event {}", name);
    }
  }

  /**
   * Events are handled by queue workers, so a step can be handled just after the recording went to
   * review. Steps (and a repeated stopped) are still taken for a short while, so the last steps
   * are not lost.
   */
  private boolean lateEvent(WebRecordingEntity recording, String event) {
    return STATUS_REVIEW.equals(recording.getStatus()) && recording.getStoppedAt() != null
        && recording.getStoppedAt().isAfter(Instant.now().minus(EVENT_GRACE))
        && ("step".equals(event) || "stopped".equals(event));
  }

  /**
   * A person pressed the record hotkey on their PC. The PC chose the id and is already capturing,
   * so this keeps the recording and sends a {@code ui.record} command that attaches to it: that
   * command is how Stop here reaches the PC, and how a PC that goes away ends the recording.
   * A repeated event (the PC resends until acked) changes nothing.
   */
  private void startedOnDevice(UUID deviceId, UUID ownerId, UUID recordingId, JsonNode data) {
    if (recordingId == null || recordings.findById(recordingId).isPresent()) return;
    DeviceDto device = devices.find(deviceId).orElse(null);
    if (device == null || !device.declares(DESKTOP_RECORD_ACTION)) {
      log.info("Dropped a desktop recording from a device that cannot record");
      return;
    }
    DeviceActionSpec spec = catalog.resolve(DESKTOP_RECORD_ACTION, 0).orElse(null);
    if (spec == null) return;
    // One recording per PC: one left open (a lost stop) goes to review.
    for (WebRecordingEntity open : recordings.findByOwnerIdAndStatusInOrderByUpdatedAtDesc(
        ownerId, List.of(STATUS_RECORDING))) {
      if (!deviceId.equals(open.getDeviceId())) continue;
      if (open.getCommandId() != null) commands.cancel(open.getCommandId(), "A new recording started");
      toReview(open);
    }

    String name = data.path("name").asText("").strip();
    String app = data.path("app").asText("").strip();
    if (name.isEmpty()) name = app.isEmpty() ? "Recording on " + device.name() : app + " task";
    if (name.length() > 120) name = name.substring(0, 120);
    WebRecordingEntity recording = new WebRecordingEntity();
    recording.setId(recordingId);
    recording.setOwnerId(ownerId);
    recording.setWorkspaceId(device.workspaceId());
    recording.setDeviceId(deviceId);
    recording.setKind(KIND_DESKTOP);
    recording.setApp(app.isEmpty() ? null : app.substring(0, Math.min(200, app.length())));
    recording.setName(name);
    recording.setStatus(STATUS_RECORDING);
    recording.setStartedAt(Instant.now());
    recording.setUpdatedAt(Instant.now());
    recordings.saveAndFlush(recording);

    ObjectNode input = Json.obj().put("recordingId", recordingId.toString()).put("attach", true)
        .put("maxMinutes", 30);
    try {
      guardrails.judge(new Access(ownerId, device.workspaceId()), device, spec, input, "Recording");
    } catch (RuntimeException e) {
      log.info("Desktop recording {} refused by the guardrails", recordingId);
      recording.setStatus(STATUS_FAILED);
      recording.setStoppedAt(Instant.now());
      recordings.save(recording);
      publish(recording, null);
      return;
    }
    DeviceCommandDto sent = commands.submit(new DeviceCommandRequest(ownerId,
        device.workspaceId(), DeviceTarget.device(deviceId), spec.action(), spec.version(), input,
        RECORD_TIMEOUT, null, null, "desktop-record:" + recordingId, true));
    recording.setCommandId(sent.id());
    recordings.save(recording);
    publish(recording, null);
  }

  private void addStep(WebRecordingEntity recording, JsonNode raw) {
    String prefix = KIND_DESKTOP.equals(recording.getKind()) ? "ui." : "browser.";
    if (!raw.isObject() || !raw.path("seq").isInt() || raw.path("seq").asInt() < 0
        || !raw.path("action").asText("").startsWith(prefix)
        || DESKTOP_RECORD_ACTION.equals(raw.path("action").asText())) {
      log.info("Dropped a malformed step for recording {}", recording.getId());
      return;
    }
    int seq = raw.get("seq").asInt();
    List<WebRecordingStepEntity> stored = steps.ordered(recording.getId());
    if (stored.size() >= MAX_STEPS) {
      log.info("Dropped a step for recording {}: too many steps", recording.getId());
      return;
    }
    if (stored.stream().anyMatch(s -> s.getSeq() == seq)) return;

    ObjectNode step = ((ObjectNode) raw).deepCopy();
    step.remove("mappings");
    boolean sensitive = step.path("meta").path("sensitive").asBoolean(false);
    if (sensitive) {
      if (step.path("input").isObject()) VALUE_FIELDS.forEach(((ObjectNode) step.get("input"))::remove);
      step.putNull("secretRef");
    }
    if (Json.write(step).length() > MAX_STEP_CHARS) {
      log.info("Dropped an oversized step for recording {}", recording.getId());
      return;
    }

    WebRecordingStepEntity entity = new WebRecordingStepEntity();
    entity.setRecordingId(recording.getId());
    entity.setSeq(seq);
    // seq only grows, so it orders steps; a review edit renumbers positions from 0
    entity.setPosition(seq);
    entity.setStep(encryption.encryptString(Json.write(step)));
    steps.save(entity);

    recording.setStepCount((int) stored.stream().filter(s -> !s.isDeleted()).count() + 1);
    if (KIND_WEB.equals(recording.getKind())) addSite(recording, step.path("meta").path("url").asText(null));
    recording.setUpdatedAt(Instant.now());
    recordings.save(recording);
    publish(recording, Json.obj().put("seq", seq).put("action", step.path("action").asText())
        .put("label", step.path("label").asText("")));
  }

  private void deviceEdit(WebRecordingEntity recording, JsonNode data) {
    String op = data.path("op").asText("");
    // The side panel may delete, move, rename, and turn a typed value into an input (never a
    // password: map refuses those). Choosing a saved login stays with the person in Jizza.
    if (!Set.of("delete", "move", "relabel", "map").contains(op) || !data.path("seq").isInt()) {
      log.info("Dropped an unsupported recording edit for {}", recording.getId());
      return;
    }
    Recordings.Input input = null;
    if ("map".equals(op)) {
      JsonNode in = data.path("input");
      if (!in.isObject() || !data.path("field").isTextual()) {
        log.info("Dropped a recording edit for {}: map needs a field and an input", recording.getId());
        return;
      }
      input = new Recordings.Input(in.path("name").asText(null), in.path("type").asText(null), null);
    }
    List<Held> held = load(recording.getId());
    try {
      apply(held, new EditOp(op, data.get("seq").asInt(),
          data.path("toIndex").isInt() ? data.get("toIndex").asInt() : null,
          data.path("label").asText(null), data.path("field").asText(null), input, null, null),
          false);
    } catch (RuntimeException e) {
      log.info("Dropped a recording edit for {}: {}", recording.getId(), e.getMessage());
      return;
    }
    persist(recording, held);
    publish(recording, null);
  }

  // ---- edits -------------------------------------------------------------------------------

  /** One editable step: its row and the decrypted JSON, written back together. */
  private static final class Held {
    final WebRecordingStepEntity entity;
    ObjectNode node;
    boolean changed;

    Held(WebRecordingStepEntity entity, ObjectNode node) {
      this.entity = entity;
      this.node = node;
    }
  }

  private void apply(List<Held> held, EditOp op, boolean person) {
    if (op == null || op.op() == null) throw new ValidationException("An edit needs an op");
    if ("insertCheck".equals(op.op())) {
      insertCheck(held, op);
      return;
    }
    Held target = held.stream().filter(h -> h.entity.getSeq() == op.seq()).findFirst()
        .orElseThrow(() -> new ValidationException("Step " + op.seq() + " is not in this recording"));
    switch (op.op()) {
      case "delete" -> {
        target.entity.setDeleted(true);
        target.changed = true;
      }
      case "move" -> move(held, target, op.toIndex());
      case "relabel" -> {
        String label = op.label() == null ? "" : op.label().strip();
        if (label.isEmpty() || label.length() > 200) {
          throw new ValidationException("A step name can be 1 to 200 characters");
        }
        target.node.put("label", label);
        target.changed = true;
      }
      case "map" -> map(target, op);
      case "secret" -> {
        if (!person) throw new ValidationException("Only the person chooses a saved login");
        secret(target, op.secretRef());
      }
      default -> throw new ValidationException("Unknown edit '" + op.op() + "'");
    }
  }

  private void move(List<Held> held, Held target, Integer toIndex) {
    if (toIndex == null || toIndex < 0) throw new ValidationException("Move needs a position");
    List<Held> live = new ArrayList<>(held.stream().filter(h -> !h.entity.isDeleted()).toList());
    live.remove(target);
    live.add(Math.min(toIndex, live.size()), target);
    List<Held> reordered = new ArrayList<>(live);
    reordered.addAll(held.stream().filter(h -> h.entity.isDeleted()).toList());
    held.clear();
    held.addAll(reordered);
    held.forEach(h -> h.changed = true);
  }

  private void map(Held target, EditOp op) {
    if (op.field() == null || op.field().isBlank()) throw new ValidationException("Choose a field");
    if (target.node.path("meta").path("sensitive").asBoolean(false)) {
      throw new ValidationException("A password can't become an input. Choose a saved login");
    }
    ObjectNode mappings = target.node.path("mappings").isObject()
        ? (ObjectNode) target.node.get("mappings") : target.node.putObject("mappings");
    if (op.input() == null) {
      mappings.remove(op.field());
      target.changed = true;
      return;
    }
    JsonNode value = StepCompiler.valueAt(target.node.path("input"), op.field());
    // A dropdown's one chosen item (items: ["Sleeper"]) is a value too; runs turn the mapped
    // text back into a list.
    if (value != null && value.isArray() && value.size() == 1) value = value.get(0);
    if (value == null || value.isNull() || !value.isValueNode()) {
      throw new ValidationException("This step has no '" + op.field() + "' value to turn into an input");
    }
    String name = op.input().name() == null ? "" : op.input().name().strip();
    if (!NAME.matcher(name).matches()) {
      throw new ValidationException("Input names use letters, numbers and underscores, "
          + "and start with a letter");
    }
    String type = op.input().type() == null ? "text" : op.input().type();
    if (!TYPES.contains(type)) throw new ValidationException("Unknown input type '" + type + "'");
    ObjectNode mapping = Json.obj().put("name", name).put("type", type);
    mapping.set("default", op.input().defaultValue() != null ? op.input().defaultValue() : value);
    mappings.set(op.field(), mapping);
    target.changed = true;
  }

  private void secret(Held target, String secretRef) {
    String ref = secretRef == null ? "" : secretRef.strip();
    if (ref.length() > 200) throw new ValidationException("That saved login is not valid");
    if (ref.isEmpty()) {
      target.node.putNull("secretRef");
    } else {
      if (target.node.path("input").isObject()) {
        VALUE_FIELDS.forEach(((ObjectNode) target.node.get("input"))::remove);
      }
      if (target.node.path("mappings").isObject()) {
        VALUE_FIELDS.forEach(((ObjectNode) target.node.get("mappings"))::remove);
      }
      target.node.put("secretRef", ref);
    }
    target.changed = true;
  }

  private void insertCheck(List<Held> held, EditOp op) {
    Check check = op.check();
    if (check == null || !CHECKS.contains(check.action())) {
      throw new ValidationException("A check is Check if element exists or Get text");
    }
    int after = -1;
    JsonNode element = null;
    String label = null;
    for (int i = 0; i < held.size(); i++) {
      Held h = held.get(i);
      if (h.entity.getSeq() == check.afterSeq()) {
        after = i;
        element = h.node.path("input").get("element");
        label = h.node.path("label").asText("the element");
      }
    }
    if (after < 0 || element == null || !element.isObject()) {
      throw new ValidationException("A check needs an earlier step that has an element");
    }
    int seq = held.stream().mapToInt(h -> h.entity.getSeq()).max().orElse(-1) + 1;
    ObjectNode step = Json.obj().put("seq", seq).put("action", check.action()).put("version", 1);
    step.set("input", Json.obj().set("element", element.deepCopy()));
    step.put("label", ("browser.exists".equals(check.action()) ? "Check that " : "Get text of ")
        + label);
    step.set("meta", Json.obj().put("sensitive", false));
    WebRecordingStepEntity entity = new WebRecordingStepEntity();
    entity.setRecordingId(held.get(after).entity.getRecordingId());
    entity.setSeq(seq);
    Held inserted = new Held(entity, step);
    inserted.changed = true;
    int index = after + 1;
    while (index < held.size() && held.get(index).entity.isDeleted()) index++;
    held.add(index, inserted);
    held.forEach(h -> h.changed = true);
  }

  private void persist(WebRecordingEntity recording, List<Held> held) {
    for (int i = 0; i < held.size(); i++) {
      Held h = held.get(i);
      if (!h.changed && h.entity.getPosition() == i) continue;
      h.entity.setPosition(i);
      h.entity.setStep(encryption.encryptString(Json.write(h.node)));
      steps.save(h.entity);
    }
    recording.setStepCount((int) held.stream().filter(h -> !h.entity.isDeleted()).count());
    recording.setUpdatedAt(Instant.now());
    recordings.save(recording);
  }

  // ---- state -------------------------------------------------------------------------------

  private void toReview(WebRecordingEntity recording) {
    recording.setStatus(STATUS_REVIEW);
    recording.setStoppedAt(Instant.now());
    recording.setUpdatedAt(Instant.now());
    recordings.save(recording);
    publish(recording, null);
  }

  /**
   * A recording whose command has finished without a stopped event is over: the person closed the
   * browser, or the device went away. It goes to review, or fails when nothing was captured.
   */
  @Transactional
  public void reconcile(WebRecordingEntity recording) {
    if (!STATUS_RECORDING.equals(recording.getStatus()) || recording.getCommandId() == null) return;
    DeviceCommandDto command = commands.find(recording.getCommandId()).orElse(null);
    if (command == null || !command.status().finished()) return;
    // The device's last events may still be in the queue when its command finishes.
    if (command.finishedAt() != null && command.finishedAt().isAfter(Instant.now().minus(EVENT_GRACE))) {
      return;
    }
    // Re-read under the row lock: steps may have been stored since this row was loaded.
    WebRecordingEntity held = recordings.lockById(recording.getId()).orElse(null);
    if (held == null || !STATUS_RECORDING.equals(held.getStatus())) return;
    recording = held;
    recording.setStepCount((int) steps.ordered(recording.getId()).stream().filter(s -> !s.isDeleted()).count());
    if (recording.getStepCount() == 0 && command.status() != com.jizza.engine.api.device.DeviceCommandStatus.SUCCEEDED) {
      recording.setStatus(STATUS_FAILED);
      recording.setStoppedAt(Instant.now());
      recording.setUpdatedAt(Instant.now());
      recordings.save(recording);
      publish(recording, null);
    } else {
      toReview(recording);
    }
  }

  /** Housekeeping: finished commands, discarded recordings and reviews nobody came back to. */
  @Transactional
  public void sweep(Instant now) {
    recordings.findByStatus(STATUS_RECORDING).forEach(this::reconcile);
    // A desktop recording whose command could not be sent ends when its time is up.
    for (WebRecordingEntity orphan : recordings.findByStatus(STATUS_RECORDING)) {
      if (orphan.getCommandId() == null && orphan.getUpdatedAt().isBefore(now.minus(RECORD_TIMEOUT))) {
        if (orphan.getStepCount() == 0) {
          orphan.setStatus(STATUS_FAILED);
          orphan.setStoppedAt(now);
          orphan.setUpdatedAt(now);
          recordings.save(orphan);
        } else {
          toReview(orphan);
        }
      }
    }
    for (WebRecordingEntity stale : recordings.findByStatusAndUpdatedAtBefore(
        STATUS_REVIEW, now.minus(Duration.ofDays(7)))) {
      stale.setStatus(STATUS_DISCARDED);
      stale.setUpdatedAt(now);
      recordings.save(stale);
    }
    for (WebRecordingEntity discarded : recordings.findByStatus(STATUS_DISCARDED)) {
      steps.deleteAllFor(discarded.getId());
    }
  }

  private static ObjectNode compile(WebRecordingEntity recording, List<JsonNode> live,
                                    boolean inline) {
    if (!KIND_DESKTOP.equals(recording.getKind())) {
      return StepCompiler.compile(recording.getStartUrl(), live, inline);
    }
    if (recording.getDeviceId() == null) {
      throw new ValidationException("The PC this was recorded on has been removed");
    }
    return StepCompiler.compileDesktop(recording.getDeviceId(), live, inline);
  }

  private WebRecordingEntity owned(Access access, UUID id) {
    return recordings.findById(id).filter(r -> r.getOwnerId().equals(access.userId()))
        .orElseThrow(() -> new NotFoundException("Recording", id));
  }

  private List<Held> load(UUID id) {
    List<Held> out = new ArrayList<>();
    for (WebRecordingStepEntity entity : steps.ordered(id)) {
      out.add(new Held(entity, (ObjectNode) Json.read(encryption.decryptString(entity.getStep()))));
    }
    return out;
  }

  private List<RecordedStep> liveAndDeleted(UUID id) {
    return toViews(load(id));
  }

  private static List<RecordedStep> toViews(List<Held> held) {
    List<RecordedStep> out = new ArrayList<>();
    for (int i = 0; i < held.size(); i++) {
      Held h = held.get(i);
      out.add(new RecordedStep(h.entity.getSeq(), i, redact(h.node), h.entity.isDeleted()));
    }
    return out;
  }

  /** Sensitive steps hold no value to begin with; this is a second line of defence. */
  private static JsonNode redact(ObjectNode step) {
    if (!step.path("meta").path("sensitive").asBoolean(false)) return step;
    ObjectNode copy = step.deepCopy();
    if (copy.path("input").isObject()) VALUE_FIELDS.forEach(((ObjectNode) copy.get("input"))::remove);
    return copy;
  }

  private Recording view(WebRecordingEntity r, String deviceName, List<RecordedStep> stepViews) {
    return new Recording(r.getId(), r.getName(), r.getStatus(), r.getDeviceId(), deviceName,
        r.getStartUrl(), r.getStepCount(), r.getWorkflowId(), r.getUpdatedAt(), stepViews,
        r.getKind(), r.getApp());
  }

  private String deviceName(WebRecordingEntity r) {
    return r.getDeviceId() == null ? null
        : devices.find(r.getDeviceId()).map(DeviceDto::name).orElse(null);
  }

  private void publish(WebRecordingEntity recording, JsonNode lastStep) {
    RealtimeGateway gateway = realtime.getIfAvailable();
    if (gateway == null) return;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("recordingId", recording.getId());
    payload.put("status", recording.getStatus());
    payload.put("stepCount", recording.getStepCount());
    if (lastStep != null) payload.put("lastStep", lastStep);
    gateway.publish(recording.getOwnerId(), TOPIC, payload);
  }

  private ReuseProfile reuseFor(SaveRequest request, ObjectNode graph, String name) {
    if (request != null && request.reuse() != null) return request.reuse();
    ArrayNode inputs = StepCompiler.inputsOf(graph);
    List<ReuseProfile.Input> declared = new ArrayList<>();
    inputs.forEach(f -> declared.add(new ReuseProfile.Input(f.path("name").asText(),
        f.path("description").asText(null), f.path("required").asBoolean(false))));
    return new ReuseProfile(name, List.of(), declared,
        new ReuseProfile.Guardrails(true, false, null, List.of(), null), null);
  }

  private void addSite(WebRecordingEntity recording, String url) {
    String host = url == null ? null : host(url);
    if (host == null) return;
    Set<String> sites = new LinkedHashSet<>(List.of(recording.getSites()));
    if (sites.size() < MAX_SITES && sites.add(host)) recording.setSites(sites.toArray(String[]::new));
  }

  private static String webUrl(String url) {
    String value = url == null ? "" : url.strip();
    try {
      URI uri = URI.create(value);
      if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null) {
        return value;
      }
    } catch (IllegalArgumentException ignored) {
      // falls through to the same message
    }
    throw new ValidationException("Start from an address that begins with http:// or https://");
  }

  private static String host(String url) {
    try {
      return URI.create(url).getHost();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static UUID uuid(String text) {
    try {
      return text == null ? null : UUID.fromString(text);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
