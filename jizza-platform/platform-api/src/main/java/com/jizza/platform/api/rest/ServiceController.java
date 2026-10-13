package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.service.ServiceModel.Action;
import com.jizza.engine.api.service.ServiceModel.ActionInput;
import com.jizza.engine.api.service.ServiceModel.Api;
import com.jizza.engine.api.service.ServiceModel.ApiInput;
import com.jizza.engine.api.service.ServiceModel.Info;
import com.jizza.engine.api.service.ServiceModel.InfoPatch;
import com.jizza.engine.api.service.ServiceModel.Resource;
import com.jizza.engine.api.service.ServiceModel.ResourceInput;
import com.jizza.engine.api.service.ServiceModel.Run;
import com.jizza.engine.api.service.ServiceModel.Trigger;
import com.jizza.engine.api.service.ServiceModel.TriggerInput;
import com.jizza.engine.api.service.ServiceModel.TriggerSample;
import com.jizza.engine.api.service.ServiceModel.Variable;
import com.jizza.engine.api.service.ServiceModel.VariableInput;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.connector.ConnectorServiceUseCase;
import com.jizza.platform.usecase.connector.ConnectorServiceUseCase.NewService;
import com.jizza.platform.usecase.connector.ConnectorServiceUseCase.ServiceDetail;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Connector (Service) editor. Authentication itself (credentials, the OAuth handshake, test and
 * revoke) stays on {@code /api/connections/{id}}, since a service's connection is its credential.
 *
 * <p>Routes that change what agents can reach (publishing, assigning actions, deleting) need a
 * fresh step-up; building a draft does not, because a draft is invisible to every agent.
 */
@RestController
@RequestMapping("/api/services")
public class ServiceController {

  /** Which agents may use an action. */
  public record AgentAssignment(List<UUID> agentIds) {}

  private final ConnectorServiceUseCase services;

  public ServiceController(ConnectorServiceUseCase services) {
    this.services = services;
  }

  @GetMapping
  public List<Info> list() {
    return services.list(CurrentUser.userId());
  }

  @PostMapping
  public Info create(@RequestBody NewService request) {
    return services.create(CurrentUser.userId(), request);
  }

  @GetMapping("/{id}")
  public ServiceDetail get(@PathVariable UUID id) {
    return services.detail(CurrentUser.userId(), id);
  }

  @PatchMapping("/{id}")
  public Info update(@PathVariable UUID id, @RequestBody InfoPatch patch) {
    return services.update(CurrentUser.userId(), id, patch);
  }

  @DeleteMapping("/{id}")
  @StepUpRequired
  public Responses.Message delete(@PathVariable UUID id) {
    services.delete(CurrentUser.userId(), id);
    return new Responses.Message("Service deleted.");
  }

  @PostMapping("/{id}/publish")
  @StepUpRequired
  public Info publish(@PathVariable UUID id) {
    return services.publish(CurrentUser.userId(), id);
  }

  @PostMapping("/{id}/unpublish")
  @StepUpRequired
  public Info unpublish(@PathVariable UUID id) {
    return services.unpublish(CurrentUser.userId(), id);
  }

  // ── APIs ──────────────────────────────────────────────────────────────────

  @PostMapping("/{id}/apis")
  public Api createApi(@PathVariable UUID id, @RequestBody ApiInput input) {
    return services.saveApi(CurrentUser.userId(), id, null, input);
  }

  @PutMapping("/{id}/apis/{apiId}")
  public Api updateApi(@PathVariable UUID id, @PathVariable UUID apiId,
                       @RequestBody ApiInput input) {
    return services.saveApi(CurrentUser.userId(), id, apiId, input);
  }

  @DeleteMapping("/{id}/apis/{apiId}")
  public Responses.Message deleteApi(@PathVariable UUID id, @PathVariable UUID apiId) {
    services.deleteApi(CurrentUser.userId(), id, apiId);
    return new Responses.Message("API deleted.");
  }

  // ── Resources ─────────────────────────────────────────────────────────────

  @PostMapping("/{id}/resources")
  public Resource createResource(@PathVariable UUID id, @RequestBody ResourceInput input) {
    return services.saveResource(CurrentUser.userId(), id, null, input);
  }

  @PutMapping("/{id}/resources/{resourceId}")
  public Resource updateResource(@PathVariable UUID id, @PathVariable UUID resourceId,
                                 @RequestBody ResourceInput input) {
    return services.saveResource(CurrentUser.userId(), id, resourceId, input);
  }

  @DeleteMapping("/{id}/resources/{resourceId}")
  public Responses.Message deleteResource(@PathVariable UUID id, @PathVariable UUID resourceId) {
    services.deleteResource(CurrentUser.userId(), id, resourceId);
    return new Responses.Message("Resource deleted.");
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  @PostMapping("/{id}/actions")
  public Action createAction(@PathVariable UUID id, @RequestBody ActionInput input) {
    return services.saveAction(CurrentUser.userId(), id, null, input);
  }

  @PutMapping("/{id}/actions/{actionId}")
  public Action updateAction(@PathVariable UUID id, @PathVariable UUID actionId,
                             @RequestBody ActionInput input) {
    return services.saveAction(CurrentUser.userId(), id, actionId, input);
  }

  @DeleteMapping("/{id}/actions/{actionId}")
  @StepUpRequired
  public Responses.Message deleteAction(@PathVariable UUID id, @PathVariable UUID actionId) {
    services.deleteAction(CurrentUser.userId(), id, actionId);
    return new Responses.Message("Action deleted.");
  }

  /** Handing an agent an action widens what it can do, so it needs a fresh re-authentication. */
  @PutMapping("/{id}/actions/{actionId}/agents")
  @StepUpRequired
  public Action assignAgents(@PathVariable UUID id, @PathVariable UUID actionId,
                             @RequestBody AgentAssignment assignment) {
    return services.assignAgents(CurrentUser.userId(), id, actionId, assignment.agentIds());
  }

  @PostMapping("/{id}/actions/{actionId}/test")
  public ToolResult testAction(@PathVariable UUID id, @PathVariable UUID actionId,
                               @RequestBody(required = false) JsonNode input) {
    return services.testAction(CurrentUser.userId(), id, actionId, input);
  }

  // ── Triggers ──────────────────────────────────────────────────────────────

  @PostMapping("/{id}/triggers")
  public Trigger createTrigger(@PathVariable UUID id, @RequestBody TriggerInput input) {
    return services.saveTrigger(CurrentUser.userId(), id, null, input);
  }

  @PutMapping("/{id}/triggers/{triggerId}")
  public Trigger updateTrigger(@PathVariable UUID id, @PathVariable UUID triggerId,
                               @RequestBody TriggerInput input) {
    return services.saveTrigger(CurrentUser.userId(), id, triggerId, input);
  }

  @DeleteMapping("/{id}/triggers/{triggerId}")
  public Responses.Message deleteTrigger(@PathVariable UUID id, @PathVariable UUID triggerId) {
    services.deleteTrigger(CurrentUser.userId(), id, triggerId);
    return new Responses.Message("Trigger deleted.");
  }

  @PostMapping("/{id}/triggers/{triggerId}/test")
  public TriggerSample testTrigger(@PathVariable UUID id, @PathVariable UUID triggerId) {
    return services.testTrigger(CurrentUser.userId(), id, triggerId);
  }

  // ── Variables ─────────────────────────────────────────────────────────────

  @PostMapping("/{id}/variables")
  public Variable createVariable(@PathVariable UUID id, @RequestBody VariableInput input) {
    return services.saveVariable(CurrentUser.userId(), id, null, input);
  }

  @PutMapping("/{id}/variables/{variableId}")
  public Variable updateVariable(@PathVariable UUID id, @PathVariable UUID variableId,
                                 @RequestBody VariableInput input) {
    return services.saveVariable(CurrentUser.userId(), id, variableId, input);
  }

  @DeleteMapping("/{id}/variables/{variableId}")
  public Responses.Message deleteVariable(@PathVariable UUID id, @PathVariable UUID variableId) {
    services.deleteVariable(CurrentUser.userId(), id, variableId);
    return new Responses.Message("Variable deleted.");
  }

  // ── History ───────────────────────────────────────────────────────────────

  @GetMapping("/{id}/history")
  public List<Run> history(@PathVariable UUID id,
                           @RequestParam(defaultValue = "100") int limit) {
    return services.history(CurrentUser.userId(), id, limit);
  }
}
