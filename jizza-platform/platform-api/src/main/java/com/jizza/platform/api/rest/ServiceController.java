package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
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
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.security.session.CurrentWorkspace;
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
@AuthenticatedOnly
@RequestMapping("/api/services")
public class ServiceController {

  /** Which agents may use an action. */
  public record AgentAssignment(List<UUID> agentIds) {}

  private final ConnectorServiceUseCase services;

  public ServiceController(ConnectorServiceUseCase services) {
    this.services = services;
  }

  @RequiresPermission(Permissions.CONNECTOR_VIEW)
  @GetMapping
  public List<Info> list() {
    return services.list(CurrentWorkspace.access());
  }

  @RequiresPermission(Permissions.CONNECTOR_CREATE)
  @PostMapping
  public Info create(@RequestBody NewService request) {
    return services.create(CurrentWorkspace.access(), request);
  }

  @RequiresPermission(Permissions.CONNECTOR_VIEW)
  @GetMapping("/{id}")
  public ServiceDetail get(@PathVariable UUID id) {
    return services.detail(CurrentWorkspace.access(), id);
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PatchMapping("/{id}")
  public Info update(@PathVariable UUID id, @RequestBody InfoPatch patch) {
    return services.update(CurrentWorkspace.access(), id, patch);
  }

  @RequiresPermission(Permissions.CONNECTOR_DELETE)
  @DeleteMapping("/{id}")
  @StepUpRequired
  public Responses.Message delete(@PathVariable UUID id) {
    services.delete(CurrentWorkspace.access(), id);
    return new Responses.Message("Service deleted.");
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PostMapping("/{id}/publish")
  @StepUpRequired
  public Info publish(@PathVariable UUID id) {
    return services.publish(CurrentWorkspace.access(), id);
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PostMapping("/{id}/unpublish")
  @StepUpRequired
  public Info unpublish(@PathVariable UUID id) {
    return services.unpublish(CurrentWorkspace.access(), id);
  }

  // ── APIs ──────────────────────────────────────────────────────────────────

  @RequiresPermission(Permissions.CONNECTOR_CREATE)
  @PostMapping("/{id}/apis")
  public Api createApi(@PathVariable UUID id, @RequestBody ApiInput input) {
    return services.saveApi(CurrentWorkspace.access(), id, null, input);
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PutMapping("/{id}/apis/{apiId}")
  public Api updateApi(@PathVariable UUID id, @PathVariable UUID apiId,
                       @RequestBody ApiInput input) {
    return services.saveApi(CurrentWorkspace.access(), id, apiId, input);
  }

  @RequiresPermission(Permissions.CONNECTOR_DELETE)
  @DeleteMapping("/{id}/apis/{apiId}")
  public Responses.Message deleteApi(@PathVariable UUID id, @PathVariable UUID apiId) {
    services.deleteApi(CurrentWorkspace.access(), id, apiId);
    return new Responses.Message("API deleted.");
  }

  // ── Resources ─────────────────────────────────────────────────────────────

  @RequiresPermission(Permissions.CONNECTOR_CREATE)
  @PostMapping("/{id}/resources")
  public Resource createResource(@PathVariable UUID id, @RequestBody ResourceInput input) {
    return services.saveResource(CurrentWorkspace.access(), id, null, input);
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PutMapping("/{id}/resources/{resourceId}")
  public Resource updateResource(@PathVariable UUID id, @PathVariable UUID resourceId,
                                 @RequestBody ResourceInput input) {
    return services.saveResource(CurrentWorkspace.access(), id, resourceId, input);
  }

  @RequiresPermission(Permissions.CONNECTOR_DELETE)
  @DeleteMapping("/{id}/resources/{resourceId}")
  public Responses.Message deleteResource(@PathVariable UUID id, @PathVariable UUID resourceId) {
    services.deleteResource(CurrentWorkspace.access(), id, resourceId);
    return new Responses.Message("Resource deleted.");
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  @RequiresPermission(Permissions.CONNECTOR_CREATE)
  @PostMapping("/{id}/actions")
  public Action createAction(@PathVariable UUID id, @RequestBody ActionInput input) {
    return services.saveAction(CurrentWorkspace.access(), id, null, input);
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PutMapping("/{id}/actions/{actionId}")
  public Action updateAction(@PathVariable UUID id, @PathVariable UUID actionId,
                             @RequestBody ActionInput input) {
    return services.saveAction(CurrentWorkspace.access(), id, actionId, input);
  }

  @RequiresPermission(Permissions.CONNECTOR_DELETE)
  @DeleteMapping("/{id}/actions/{actionId}")
  @StepUpRequired
  public Responses.Message deleteAction(@PathVariable UUID id, @PathVariable UUID actionId) {
    services.deleteAction(CurrentWorkspace.access(), id, actionId);
    return new Responses.Message("Action deleted.");
  }

  /** Handing an agent an action widens what it can do, so it needs a fresh re-authentication. */
  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PutMapping("/{id}/actions/{actionId}/agents")
  @StepUpRequired
  public Action assignAgents(@PathVariable UUID id, @PathVariable UUID actionId,
                             @RequestBody AgentAssignment assignment) {
    return services.assignAgents(CurrentWorkspace.access(), id, actionId, assignment.agentIds());
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PostMapping("/{id}/actions/{actionId}/test")
  public ToolResult testAction(@PathVariable UUID id, @PathVariable UUID actionId,
                               @RequestBody(required = false) JsonNode input) {
    return services.testAction(CurrentWorkspace.access(), id, actionId, input);
  }

  // ── Triggers ──────────────────────────────────────────────────────────────

  @RequiresPermission(Permissions.CONNECTOR_CREATE)
  @PostMapping("/{id}/triggers")
  public Trigger createTrigger(@PathVariable UUID id, @RequestBody TriggerInput input) {
    return services.saveTrigger(CurrentWorkspace.access(), id, null, input);
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PutMapping("/{id}/triggers/{triggerId}")
  public Trigger updateTrigger(@PathVariable UUID id, @PathVariable UUID triggerId,
                               @RequestBody TriggerInput input) {
    return services.saveTrigger(CurrentWorkspace.access(), id, triggerId, input);
  }

  @RequiresPermission(Permissions.CONNECTOR_DELETE)
  @DeleteMapping("/{id}/triggers/{triggerId}")
  public Responses.Message deleteTrigger(@PathVariable UUID id, @PathVariable UUID triggerId) {
    services.deleteTrigger(CurrentWorkspace.access(), id, triggerId);
    return new Responses.Message("Trigger deleted.");
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PostMapping("/{id}/triggers/{triggerId}/test")
  public TriggerSample testTrigger(@PathVariable UUID id, @PathVariable UUID triggerId) {
    return services.testTrigger(CurrentWorkspace.access(), id, triggerId);
  }

  // ── Variables ─────────────────────────────────────────────────────────────

  @RequiresPermission(Permissions.CONNECTOR_CREATE)
  @PostMapping("/{id}/variables")
  public Variable createVariable(@PathVariable UUID id, @RequestBody VariableInput input) {
    return services.saveVariable(CurrentWorkspace.access(), id, null, input);
  }

  @RequiresPermission(Permissions.CONNECTOR_MODIFY)
  @PutMapping("/{id}/variables/{variableId}")
  public Variable updateVariable(@PathVariable UUID id, @PathVariable UUID variableId,
                                 @RequestBody VariableInput input) {
    return services.saveVariable(CurrentWorkspace.access(), id, variableId, input);
  }

  @RequiresPermission(Permissions.CONNECTOR_DELETE)
  @DeleteMapping("/{id}/variables/{variableId}")
  public Responses.Message deleteVariable(@PathVariable UUID id, @PathVariable UUID variableId) {
    services.deleteVariable(CurrentWorkspace.access(), id, variableId);
    return new Responses.Message("Variable deleted.");
  }

  // ── History ───────────────────────────────────────────────────────────────

  @RequiresPermission(Permissions.CONNECTOR_VIEW)
  @GetMapping("/{id}/history")
  public List<Run> history(@PathVariable UUID id,
                           @RequestParam(defaultValue = "100") int limit) {
    return services.history(CurrentWorkspace.access(), id, limit);
  }
}
