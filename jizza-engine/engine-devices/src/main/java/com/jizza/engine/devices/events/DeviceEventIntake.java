package com.jizza.engine.devices.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.queue.JobQueue;
import com.jizza.engine.api.queue.JobTypes;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.RecordingIntake;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.DeviceRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Things that happen on a device and start workflows: a file landing in a watched folder, a phone
 * arriving somewhere, "Run this" from a share sheet. An event is queued as a durable job first, so
 * the device gets its ack at once and a restart never loses the event.
 *
 * <p>A workflow listens by its trigger's config: {@code {"source":"device","event":"file.added"}},
 * optionally with {@code deviceId} for one device. Only approved, enabled workflows of the device's
 * owner start, as with every unattended trigger.
 */
@Component
public class DeviceEventIntake {

  private static final Logger log = LoggerFactory.getLogger(DeviceEventIntake.class);
  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
  private static final String RECORDING_PREFIX = "browser.recording.";
  private static final String DESKTOP_RECORDING_PREFIX = "desktop.recording.";
  /** Handled before the ack, so the recording exists before the PC's first step is read. */
  private static final String DESKTOP_STARTED = "desktop.recording.started";
  private static final int MAX_DATA = 64 * 1024;

  private final ObjectProvider<JobQueue> queue;
  private final DeviceRepository devices;
  private final WorkflowRepository workflows;
  private final ObjectProvider<WorkflowEngine> engine;
  private final AuditLog audit;
  private final ObjectProvider<RecordingIntake> recordingIntake;

  public DeviceEventIntake(ObjectProvider<JobQueue> queue, DeviceRepository devices,
                           WorkflowRepository workflows, ObjectProvider<WorkflowEngine> engine,
                           AuditLog audit, ObjectProvider<RecordingIntake> recordingIntake) {
    this.queue = queue;
    this.devices = devices;
    this.workflows = workflows;
    this.engine = engine;
    this.audit = audit;
    this.recordingIntake = recordingIntake;
  }

  @PostConstruct
  void register() {
    JobQueue q = queue.getIfAvailable();
    if (q != null) q.register(JobTypes.DEVICE_EVENT, job -> handle(job.payload()));
  }

  /** Checks and queues one event; the device is acked once this returns. */
  public void accept(UUID deviceId, UUID userId, JsonNode body) {
    String name = body.path("name").asText("");
    if (!NAME.matcher(name).matches()) {
      throw new ValidationException("An event needs a name such as file.added");
    }
    JsonNode data = body.path("data").isObject() ? body.get("data") : Json.obj();
    if (Json.write(data).length() > MAX_DATA) {
      throw new ValidationException("An event's data can be at most 64 KB");
    }
    ObjectNode payload = Json.obj().put("deviceId", deviceId.toString()).put("name", name);
    if (userId != null) payload.put("userId", userId.toString());
    payload.set("data", data);
    JobQueue q = queue.getIfAvailable();
    if (q == null || DESKTOP_STARTED.equals(name)) {
      handle(payload);
    } else {
      q.enqueue(userId, JobTypes.DEVICE_EVENT, payload);
    }
  }

  /** Starts every listening workflow. Returns how many started. */
  public int handle(JsonNode payload) {
    UUID deviceId = UUID.fromString(payload.path("deviceId").asText());
    DeviceEntity device = devices.findById(deviceId).orElse(null);
    if (device == null || !"active".equals(device.getStatus())) return 0;
    UUID owner = device.getOwnerUserId() != null ? device.getOwnerUserId() : device.getEnrolledBy();
    if (owner == null) return 0;
    String name = payload.path("name").asText();
    if (name.startsWith(RECORDING_PREFIX) || name.startsWith(DESKTOP_RECORDING_PREFIX)) {
      // A recording is a person working in their browser; its events never start workflows.
      RecordingIntake recordings = recordingIntake.getIfAvailable();
      if (recordings != null) recordings.accept(deviceId, owner, name, payload.path("data"));
      return 0;
    }
    WorkflowEngine runner = engine.getIfAvailable();
    if (runner == null) return 0;
    ObjectNode input = Json.obj().put("event", name).put("deviceId", deviceId.toString())
        .put("deviceName", device.getName());
    input.set("data", payload.path("data"));
    audit.record(owner, ActorType.SYSTEM, null, "device.event_received", device.getName(),
        Json.obj().put("deviceId", deviceId.toString()).put("event", name));
    int started = 0;
    for (WorkflowEntity workflow : workflows.findByUserIdAndStatus(owner, WorkflowStatus.APPROVED)) {
      if (workflow.isDisabled() || !listens(workflow, deviceId, name)) continue;
      try {
        runner.start(workflow.getId(), workflow.getVersion(), input, "device:" + deviceId);
        started++;
      } catch (RuntimeException e) {
        log.warn("Device event {} could not start workflow {}", name, workflow.getId(), e);
      }
    }
    return started;
  }

  static boolean listens(WorkflowEntity workflow, UUID deviceId, String name) {
    WorkflowNode trigger;
    try {
      trigger = WorkflowGraph.from(workflow.getGraph()).trigger();
    } catch (RuntimeException e) {
      return false;
    }
    JsonNode config = trigger.config();
    if (config == null) return false;
    String source = config.path("source").asText("");
    if (!source.equals("device") && !source.equals("device_event")) return false;
    String event = config.path("event").asText("");
    if (!event.equals(name) && !event.equals("*")) return false;
    String only = config.path("deviceId").asText("");
    return only.isBlank() || only.equals(deviceId.toString());
  }
}
