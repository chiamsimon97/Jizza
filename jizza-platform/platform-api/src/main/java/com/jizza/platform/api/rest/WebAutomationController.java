package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.api.webauto.BrowserAgents;
import com.jizza.engine.api.webauto.DesignTimeService;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.platform.usecase.webauto.WebAutomationUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Design-time browser discovery and literal step testing for the workflow builder. */
@RestController
@RequestMapping("/api/web")
public class WebAutomationController {

  public record TestStepRequest(UUID deviceId, String action, Integer version, JsonNode inputs) {}

  public record PickRequest(UUID deviceId, String sessionId, String url, String prompt) {}

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
}
