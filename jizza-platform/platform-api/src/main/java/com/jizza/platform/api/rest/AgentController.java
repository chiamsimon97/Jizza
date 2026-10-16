package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.connector.ConnectionStore;
import com.jizza.engine.api.connector.ToolCatalog;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.dto.ConnectionDto;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.error.PermissionDeniedException;
import com.jizza.engine.api.service.ServiceDefinitions;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.agent.CapabilityRegistry;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.platform.usecase.agent.AgentAdminUseCase;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The agent roster: rename, enable/disable, and capability assignment (R21/R28). */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/agents")
public class AgentController {

  private final AgentDirectory directory;
  private final AgentAdminUseCase admin;
  private final CapabilityRegistry capabilities;
  private final ToolCatalog tools;
  private final ServiceDefinitions services;
  private final ConnectionStore connections;
  private final PermissionEvaluator permissions;

  public AgentController(AgentDirectory directory, AgentAdminUseCase admin,
                         CapabilityRegistry capabilities, ToolCatalog tools,
                         ServiceDefinitions services, ConnectionStore connections,
                         PermissionEvaluator permissions) {
    this.directory = directory;
    this.admin = admin;
    this.capabilities = capabilities;
    this.tools = tools;
    this.services = services;
    this.connections = connections;
    this.permissions = permissions;
  }

  @GetMapping
  public List<AgentDto> list() {
    return directory.list(CurrentUser.userId());
  }

  @GetMapping("/{id}")
  public AgentDto get(@PathVariable UUID id) {
    return owned(id);
  }

  @PatchMapping("/{id}")
  public AgentDto patch(@PathVariable UUID id, @Valid @RequestBody Requests.AgentPatch patch) {
    owned(id);
    AgentDto result = null;
    if (patch.name() != null) result = admin.rename(CurrentUser.userId(), id, patch.name());
    if (patch.enabled() != null) {
      result = admin.setEnabled(CurrentUser.userId(), id, patch.enabled());
    }
    return result == null ? owned(id) : result;
  }

  @GetMapping("/{id}/tools")
  public List<com.jizza.engine.api.tool.ToolSpec> tools(@PathVariable UUID id) {
    owned(id);
    return capabilities.toolsFor(id);
  }

  /** Assigning a capability widens what an agent can do, so it needs a fresh re-authentication. */
  @PostMapping("/{id}/capabilities")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message grant(@PathVariable UUID id,
                                 @Valid @RequestBody Requests.CapabilityGrant grant) {
    owned(id);
    requireConnectorAccess(grant.type(), grant.capabilityRef());
    capabilities.grant(id, grant.type(), grant.capabilityRef());
    return new Responses.Message("Capability assigned.");
  }

  @DeleteMapping("/{id}/capabilities/{type}/{ref}")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message revoke(@PathVariable UUID id, @PathVariable CapabilityType type,
                                  @PathVariable UUID ref) {
    owned(id);
    capabilities.revoke(id, type, ref);
    return new Responses.Message("Capability removed.");
  }

  /**
   * Agents are personal, but may be handed a tool from an organization's connector. That is only
   * allowed while the agent's owner (the caller) can see connectors in that organization.
   */
  private void requireConnectorAccess(CapabilityType type, UUID ref) {
    UUID connectionId = switch (type) {
      case GENERATED_TOOL -> tools.find(ref).map(GeneratedToolDto::connectionId).orElse(null);
      case SERVICE_ACTION -> services.action(ref).map(a -> a.serviceId()).orElse(null);
      default -> null;
    };
    if (connectionId == null) return;
    UUID workspaceId = connections.find(connectionId).map(ConnectionDto::workspaceId).orElse(null);
    if (workspaceId == null
        || !permissions.isAllowed(CurrentUser.userId(), workspaceId, Permissions.CONNECTOR_VIEW)) {
      throw new PermissionDeniedException(Permissions.CONNECTOR_VIEW, "connectors");
    }
  }

  /** Every lookup is scoped to the signed-in user; another user's agent is simply not found. */
  private AgentDto owned(UUID agentId) {
    return directory.byId(agentId)
        .filter(agent -> agent.userId().equals(CurrentUser.userId()))
        .orElseThrow(() -> new NotFoundException("Agent", agentId));
  }
}
