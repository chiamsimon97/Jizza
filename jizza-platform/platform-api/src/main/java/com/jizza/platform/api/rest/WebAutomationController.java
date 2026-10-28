package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.api.webauto.BrowserAgents;
import com.jizza.engine.api.webauto.DesignTimeService;
import com.jizza.engine.api.webauto.Recordings;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.platform.usecase.webauto.WebAutomationUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Design-time browser discovery and literal step testing for the workflow builder. */
@RestController
@RequestMapping("/api/web")
public class WebAutomationController {

  public record TestStepRequest(UUID deviceId, String action, Integer version, JsonNode inputs) {}

  public record PickRequest(UUID deviceId, String sessionId, String url, String prompt) {}

  public record StartRecordingRequest(UUID deviceId, String url, String name) {}

  public record EditStepsRequest(List<Recordings.EditOp> ops) {}

  private final WebAutomationUseCase web;

  public WebAutomationController(WebAutomationUseCase web) {
    this.web = web;
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @GetMapping("/browsers")
  public List<BrowserAgents.Browser> browsers() {
    return web.browsers(CurrentWorkspace.access());
  }

  @RequiresPermission(Permissions.DEVICE_USE)
  @PostMapping("/test-step")
  public DesignTimeService.Result testStep(@RequestBody TestStepRequest request) {
    return web.testStep(CurrentWorkspace.access(), request.deviceId(), request.action(),
        request.version(), request.inputs());
  }

  @RequiresPermission(Permissions.DEVICE_USE)
  @PostMapping("/pick")
  public DesignTimeService.PickStarted pick(@RequestBody PickRequest request) {
    return web.startPick(CurrentWorkspace.access(), request.deviceId(), request.sessionId(),
        request.url(), request.prompt());
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @GetMapping("/pick/{commandId}")
  public DesignTimeService.PickResult pickResult(@PathVariable UUID commandId) {
    return web.pickResult(CurrentWorkspace.access(), commandId);
  }

  @RequiresPermission(Permissions.DEVICE_USE)
  @PostMapping("/recordings")
  public Recordings.Recording startRecording(@RequestBody StartRecordingRequest request) {
    return web.startRecording(CurrentWorkspace.access(), request.deviceId(), request.url(),
        request.name());
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @GetMapping("/recordings")
  public List<Recordings.Recording> recordings(@RequestParam(required = false) List<String> status) {
    return web.recordings(CurrentWorkspace.access(), status);
  }

  @RequiresPermission(Permissions.DEVICE_VIEW)
  @GetMapping("/recordings/{id}")
  public Recordings.Recording recording(@PathVariable UUID id) {
    return web.recording(CurrentWorkspace.access(), id);
  }

  @RequiresPermission(Permissions.DEVICE_USE)
  @PatchMapping("/recordings/{id}/steps")
  public Recordings.Recording editSteps(@PathVariable UUID id, @RequestBody EditStepsRequest request) {
    return web.editRecording(CurrentWorkspace.access(), id, request.ops());
  }

  @RequiresPermission(Permissions.DEVICE_USE)
  @PostMapping("/recordings/{id}/stop")
  public Recordings.Recording stopRecording(@PathVariable UUID id) {
    return web.stopRecording(CurrentWorkspace.access(), id);
  }

  @RequiresPermission(Permissions.DEVICE_USE)
  @DeleteMapping("/recordings/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void discardRecording(@PathVariable UUID id) {
    web.discardRecording(CurrentWorkspace.access(), id);
  }

  @RequiresPermission(Permissions.DEVICE_USE)
  @PostMapping("/recordings/{id}/save")
  public Recordings.SaveResult saveRecording(@PathVariable UUID id,
                                             @RequestBody Recordings.SaveRequest request) {
    return web.saveRecording(CurrentWorkspace.access(), id, request);
  }

  /** Steps for a workflow being built; not in the saved-module routes above. */
  @RequiresPermission(Permissions.DEVICE_USE)
  @PostMapping("/recordings/{id}/insert")
  public JsonNode insertRecording(@PathVariable UUID id) {
    return web.insertableRecording(CurrentWorkspace.access(), id);
  }
}
