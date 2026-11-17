package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandRequest;
import com.jizza.engine.api.device.DeviceCommandStatus;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.device.DeviceTarget;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.security.session.CurrentUser;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live view (plan 06): a picture of the tab a run is working in, taken when the person asks, so
 * they can watch the run from the web app. Pictures are taken only on request, by the run's own
 * browser, of the run's own tab.
 */
@RestController
@RequestMapping("/api/runs")
@AuthenticatedOnly
public class LiveViewController {

  /** How long one request waits for the picture before saying it is still coming. */
  private static final Duration WAIT = Duration.ofSeconds(6);

  public record Picture(String status, String commandId, String device, String file, String problem) {}

  private final WorkflowEngine engine;
  private final ObjectProvider<DeviceCommands> commands;

  public LiveViewController(WorkflowEngine engine, ObjectProvider<DeviceCommands> commands) {
    this.engine = engine;
    this.commands = commands;
  }

  /** Asks the run's browser for a picture of its tab now. */
  @PostMapping("/{id}/live-view")
  public Picture take(@PathVariable UUID id) {
    RunDto run = owned(id);
    JsonNode browser = run.context() == null ? null : run.context().path("browser");
    if (browser == null || !browser.path("deviceId").isTextual() || !browser.path("sessionId").isTextual()) {
      throw new ValidationException("This run has no browser open yet");
    }
    DeviceCommands broker = broker();
    UUID workspaceId = browser.path("workspaceId").isTextual()
        ? UUID.fromString(browser.get("workspaceId").asText()) : null;
    DeviceCommandDto shot = broker.submit(new DeviceCommandRequest(run.userId(), workspaceId,
        DeviceTarget.device(UUID.fromString(browser.get("deviceId").asText())), "browser.screenshot", 1,
        Json.obj().put("session", browser.get("sessionId").asText()).put("format", "jpeg")
            .put("screenshotOnError", false),
        Duration.ofSeconds(30), run.id(), "live-view", "live-view:" + UUID.randomUUID(), true));
    return picture(broker.await(shot.id(), WAIT));
  }

  /** The picture asked for, once the browser has sent it. */
  @GetMapping("/{id}/live-view/{commandId}")
  public Picture get(@PathVariable UUID id, @PathVariable UUID commandId) {
    RunDto run = owned(id);
    DeviceCommands broker = broker();
    DeviceCommandDto shot = broker.find(commandId)
        .filter(c -> run.id().equals(c.runId()) && "live-view".equals(c.nodeId()))
        .orElseThrow(() -> new NotFoundException("Picture", commandId));
    return picture(shot.status().finished() ? shot : broker.await(commandId, WAIT));
  }

  private static Picture picture(DeviceCommandDto shot) {
    if (!shot.status().finished()) {
      return new Picture("pending", shot.id().toString(), null, null, null);
    }
    String file = shot.output() == null ? null : shot.output().path("file").asText(null);
    if (shot.status() != DeviceCommandStatus.SUCCEEDED || file == null) {
      return new Picture("failed", shot.id().toString(), null, null, shot.explain());
    }
    return new Picture("ready", shot.id().toString(), shot.deviceId().toString(), file, null);
  }

  private DeviceCommands broker() {
    DeviceCommands broker = commands.getIfAvailable();
    if (broker == null) throw new ValidationException("Devices are not available on this server");
    return broker;
  }

  private RunDto owned(UUID runId) {
    RunDto run = engine.get(runId);
    if (!run.userId().equals(CurrentUser.userId())) throw new NotFoundException("Workflow run", runId);
    return run;
  }
}
