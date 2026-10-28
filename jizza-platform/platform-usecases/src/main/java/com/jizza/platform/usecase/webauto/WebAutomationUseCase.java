package com.jizza.platform.usecase.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.BrowserAgents;
import com.jizza.engine.api.webauto.DesignTimeService;
import com.jizza.engine.api.webauto.Recordings;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Manual web-step design actions, kept behind device ownership checks in the engine ports. */
@Service
public class WebAutomationUseCase {

  private final BrowserAgents browsers;
  private final DesignTimeService designTime;
  private final Recordings recordings;
  private final AuditLog audit;

  public WebAutomationUseCase(BrowserAgents browsers, DesignTimeService designTime,
                              Recordings recordings, AuditLog audit) {
    this.browsers = browsers;
    this.designTime = designTime;
    this.recordings = recordings;
    this.audit = audit;
  }

  public List<BrowserAgents.Browser> browsers(Access access) {
    return browsers.list(access);
  }

  public DesignTimeService.Result testStep(Access access, UUID deviceId, String action,
                                           Integer version, JsonNode inputs) {
    DesignTimeService.Result result =
        designTime.testStep(access, deviceId, action, version, inputs);
    audit.record(access.userId(), ActorType.USER, null, "web.step_tested", action,
        Json.obj().put("deviceId", deviceId.toString()).put("status", result.status()));
    return result;
  }

  public DesignTimeService.PickStarted startPick(Access access, UUID deviceId, String sessionId,
                                                 String url, String prompt) {
    DesignTimeService.PickStarted started =
        designTime.startPick(access, deviceId, sessionId, url, prompt);
    audit.record(access.userId(), ActorType.USER, null, "web.pick_started",
        started.commandId().toString(), Json.obj().put("deviceId", deviceId.toString()));
    return started;
  }

  public DesignTimeService.PickResult pickResult(Access access, UUID commandId) {
    return designTime.pickResult(access, commandId);
  }

  public Recordings.Recording startRecording(Access access, UUID deviceId, String url, String name) {
    Recordings.Recording started = recordings.start(access, deviceId, url, name);
    audit.record(access.userId(), ActorType.USER, null, "web.recording_started", started.name(),
        Json.obj().put("recordingId", started.id().toString()).put("deviceId", deviceId.toString()));
    return started;
  }

  public List<Recordings.Recording> recordings(Access access, Collection<String> statuses) {
    return recordings.list(access, statuses);
  }

  public Recordings.Recording recording(Access access, UUID id) {
    return recordings.get(access, id);
  }

  public Recordings.Recording editRecording(Access access, UUID id, List<Recordings.EditOp> ops) {
    return recordings.edit(access, id, ops);
  }

  public Recordings.Recording stopRecording(Access access, UUID id) {
    return recordings.stop(access, id);
  }

  public void discardRecording(Access access, UUID id) {
    recordings.discard(access, id);
    audit.record(access.userId(), ActorType.USER, null, "web.recording_discarded", id.toString(),
        Json.obj().put("recordingId", id.toString()));
  }

  public Recordings.SaveResult saveRecording(Access access, UUID id, Recordings.SaveRequest request) {
    Recordings.SaveResult saved = recordings.save(access, id, request);
    audit.record(access.userId(), ActorType.USER, null, "web.recording_saved", id.toString(),
        Json.obj().put("recordingId", id.toString()).put("workflowId", saved.workflowId().toString())
            .put("version", saved.version()));
    return saved;
  }

  public JsonNode insertableRecording(Access access, UUID id) {
    JsonNode graph = recordings.insertable(access, id);
    audit.record(access.userId(), ActorType.USER, null, "web.recording_inserted", id.toString(),
        Json.obj().put("recordingId", id.toString()));
    return graph;
  }
}
