package com.jizza.engine.connectors.tools.service;

import com.jizza.engine.api.connector.IdempotencyKey;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.service.ServiceModel;
import com.jizza.engine.api.service.ServiceRunner;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentCapabilityEntity;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.entity.ServiceActionEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.ServiceActionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Offers service actions to agents as tools (R28).
 *
 * <p>An action reaches an agent only when all of these hold: the agent was granted that action,
 * the action and its API are enabled, and the service is published as live. A draft service is
 * invisible to every agent, so half-built definitions never end up in a model's tool list.
 */
@Component
public class ServiceActionToolProvider implements ToolProvider {

  private final AgentCapabilityRepository capabilities;
  private final ServiceActionRepository actions;
  private final GeneratedToolRepository apis;
  private final ConnectionRepository services;
  private final AgentRepository agents;
  private final ServiceRunner runner;

  public ServiceActionToolProvider(AgentCapabilityRepository capabilities,
                                   ServiceActionRepository actions, GeneratedToolRepository apis,
                                   ConnectionRepository services, AgentRepository agents,
                                   ServiceRunner runner) {
    this.capabilities = capabilities;
    this.actions = actions;
    this.apis = apis;
    this.services = services;
    this.agents = agents;
    this.runner = runner;
  }

  private record Usable(ServiceActionEntity action, GeneratedToolEntity api,
                        ConnectionEntity service, String toolName) {}

  @Override
  @Transactional(readOnly = true)
  public List<ToolSpec> toolsFor(UUID agentId) {
    return usable(agentId).stream().map(u -> new ToolSpec(u.toolName(), describe(u),
        u.api().getInputSchema(), u.api().isSideEffecting())).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean supports(UUID agentId, String toolName) {
    return find(agentId, toolName).isPresent();
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<Usable> found = find(agentId, call.name());
    if (found.isEmpty()) {
      return ToolResult.error("Tool '" + call.name() + "' is not available to this agent");
    }
    Usable u = found.get();

    // Same idempotency rule as generated tools: a retried turn re-uses the model's tool_use id.
    IdempotencyKey key = null;
    if (u.api().isSideEffecting()) {
      UUID userId = agents.findById(agentId).map(AgentEntity::getUserId).orElse(null);
      if (userId != null) {
        String reference = call.id() != null ? call.id()
            : Hashing.sha256Hex(agentId + "|" + call.name() + "|" + Json.write(call.args()));
        key = IdempotencyKey.of(userId, "service-action:" + reference);
      }
    }
    return runner.runAction(u.service().getId(), u.action().getId(), call.args(), "agent", key);
  }

  /** A service action belongs to its service's workspace. */
  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> workspaceOf(UUID agentId, String toolName) {
    return find(agentId, toolName).map(u -> u.service().getWorkspaceId());
  }

  private Optional<Usable> find(UUID agentId, String toolName) {
    return usable(agentId).stream().filter(u -> u.toolName().equals(toolName)).findFirst();
  }

  private List<Usable> usable(UUID agentId) {
    List<UUID> refs = capabilities
        .findByAgentIdAndCapabilityType(agentId, CapabilityType.SERVICE_ACTION).stream()
        .map(AgentCapabilityEntity::getCapabilityRef).toList();
    if (refs.isEmpty()) return List.of();

    List<Usable> out = new ArrayList<>();
    for (ServiceActionEntity action : actions.findAllById(refs)) {
      if (!action.isEnabled()) continue;
      ConnectionEntity service = services.findById(action.getConnectionId()).orElse(null);
      if (service == null || !ServiceModel.LIVE.equals(service.getVersionStatus())) continue;
      GeneratedToolEntity api = apis.findById(action.getApiId()).orElse(null);
      if (api == null || !api.isEnabled()) continue;
      out.add(new Usable(action, api, service,
          JpaServiceDefinitions.toolName(service.getSlug(), action.getName())));
    }
    return out;
  }

  private static String describe(Usable u) {
    String what = u.action().getDescription() == null || u.action().getDescription().isBlank()
        ? u.action().getLabel() : u.action().getLabel() + ". " + u.action().getDescription();
    return u.service().getServiceName() + ": " + what;
  }
}
