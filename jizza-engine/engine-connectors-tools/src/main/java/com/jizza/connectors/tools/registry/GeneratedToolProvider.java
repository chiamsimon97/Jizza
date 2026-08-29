package com.jizza.connectors.tools.registry;

import com.jizza.common.connector.IdempotencyKey;
import com.jizza.common.connector.ToolExecutor;
import com.jizza.common.dto.GeneratedToolDto;
import com.jizza.common.enums.CapabilityType;
import com.jizza.common.tool.ToolCall;
import com.jizza.common.tool.ToolProvider;
import com.jizza.common.tool.ToolResult;
import com.jizza.common.tool.ToolSpec;
import com.jizza.persistence.entity.AgentCapabilityEntity;
import com.jizza.persistence.entity.GeneratedToolEntity;
import com.jizza.persistence.repository.AgentCapabilityRepository;
import com.jizza.persistence.repository.AgentRepository;
import com.jizza.persistence.repository.GeneratedToolRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exposes the generated tools an agent has actually been granted (R28). The allow-list is
 * {@code agent_capabilities}: a tool that exists on a connection is not reachable by an agent until
 * it is assigned, so connecting a service does not implicitly widen what any agent can do.
 */
@Component
public class GeneratedToolProvider implements ToolProvider {

  private final AgentCapabilityRepository capabilities;
  private final GeneratedToolRepository tools;
  private final AgentRepository agents;
  private final ToolExecutor executor;

  public GeneratedToolProvider(AgentCapabilityRepository capabilities, GeneratedToolRepository tools,
                               AgentRepository agents, ToolExecutor executor) {
    this.capabilities = capabilities;
    this.tools = tools;
    this.agents = agents;
    this.executor = executor;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ToolSpec> toolsFor(UUID agentId) {
    return granted(agentId).stream()
        .filter(GeneratedToolEntity::isEnabled)
        .map(t -> new ToolSpec(t.getName(), t.getDescription(), t.getInputSchema(),
            t.isSideEffecting()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean supports(UUID agentId, String toolName) {
    return find(agentId, toolName).isPresent();
  }

  @Override
  @Transactional(readOnly = true)
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<GeneratedToolEntity> tool = find(agentId, call.name());
    if (tool.isEmpty()) {
      return ToolResult.error("Tool '" + call.name() + "' is not assigned to this agent");
    }
    GeneratedToolDto dto = toDto(tool.get());

    // A side-effecting call gets a key derived from the model's own tool_use id, so a retry of the
    // same turn re-uses it and the service sees one request rather than two.
    IdempotencyKey key = null;
    if (dto.sideEffecting()) {
      UUID userId = agents.findById(agentId)
          .map(com.jizza.persistence.entity.AgentEntity::getUserId).orElse(null);
      if (userId != null) {
        String reference = call.id() != null ? call.id()
            : com.jizza.common.util.Hashing.sha256Hex(
                agentId + "|" + call.name() + "|" + com.jizza.common.util.Json.write(call.args()));
        key = IdempotencyKey.of(userId, "tool:" + reference);
      }
    }
    return executor.execute(dto, call.args(), key);
  }

  private Optional<GeneratedToolEntity> find(UUID agentId, String toolName) {
    return granted(agentId).stream()
        .filter(t -> t.getName().equals(toolName) && t.isEnabled())
        .findFirst();
  }

  private List<GeneratedToolEntity> granted(UUID agentId) {
    List<UUID> refs = capabilities
        .findByAgentIdAndCapabilityType(agentId, CapabilityType.GENERATED_TOOL).stream()
        .map(AgentCapabilityEntity::getCapabilityRef).toList();
    return refs.isEmpty() ? List.of() : tools.findAllById(refs);
  }

  public static GeneratedToolDto toDto(GeneratedToolEntity t) {
    return new GeneratedToolDto(t.getId(), t.getConnectionId(), t.getName(), t.getDescription(),
        t.getInputSchema(), t.getHttpMethod(), t.getPathTemplate(), t.getParamMapping(),
        t.getAuthMapping(), t.isSideEffecting(), t.isEnabled());
  }
}
