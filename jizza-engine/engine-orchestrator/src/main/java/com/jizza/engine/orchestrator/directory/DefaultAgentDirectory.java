package com.jizza.engine.orchestrator.directory;

import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared read model over {@code agents} so other modules can name an agent without a dependency. */
@Service
public class DefaultAgentDirectory implements AgentDirectory {

  private final AgentRepository agents;

  public DefaultAgentDirectory(AgentRepository agents) {
    this.agents = agents;
  }

  @Override
  @Transactional(readOnly = true)
  public List<AgentDto> list(UUID userId) {
    return agents.findByUserIdOrderByIsOrchestratorDescNameAsc(userId).stream()
        .map(DefaultAgentDirectory::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AgentDto> byId(UUID agentId) {
    return agents.findById(agentId).map(DefaultAgentDirectory::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AgentDto> byKey(UUID userId, String key) {
    return agents.findByUserIdAndKey(userId, key).map(DefaultAgentDirectory::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public AgentDto orchestrator(UUID userId) {
    return agents.findByUserIdAndIsOrchestratorTrue(userId).map(DefaultAgentDirectory::toDto)
        .orElseThrow(() -> new NotFoundException("Orchestrator agent for user", userId));
  }

  @Override
  @Transactional(readOnly = true)
  public String nameOf(UUID agentId) {
    return agents.findById(agentId).map(AgentEntity::getName).orElse("Unknown agent");
  }

  @Override
  @Transactional(readOnly = true)
  public List<AgentDto> reportsOf(UUID agentId) {
    return agents.findByParentAgentIdOrderByNameAsc(agentId).stream()
        .map(DefaultAgentDirectory::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<AgentDto> fromTemplate(UUID templateId) {
    return agents.findByOriginTemplateId(templateId).stream()
        .map(DefaultAgentDirectory::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<AgentDto> managersOf(UUID agentId) {
    return managers(agents, agentId).stream().map(DefaultAgentDirectory::toDto).toList();
  }

  /**
   * The agent's managers, nearest first. Bounded and cycle-safe, so a bad row can never hang a
   * caller that walks up the team.
   */
  public static List<AgentEntity> managers(AgentRepository agents, UUID agentId) {
    List<AgentEntity> chain = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    seen.add(agentId);
    UUID next = agents.findById(agentId).map(AgentEntity::getParentAgentId).orElse(null);
    while (next != null && seen.add(next) && chain.size() < MAX_WALK) {
      AgentEntity manager = agents.findById(next).orElse(null);
      if (manager == null) break;
      chain.add(manager);
      next = manager.getParentAgentId();
    }
    return chain;
  }

  private static final int MAX_WALK = 16;

  public static AgentDto toDto(AgentEntity a) {
    return new AgentDto(a.getId(), a.getUserId(), a.getKey(), a.getName(), a.getDomain(),
        a.getSystemPrompt(), a.isOrchestrator(), a.isEnabled(), a.isCustom(), a.getDescription(),
        a.getStatus(), a.getModelTier(), List.of(a.getMemoryDomains()), a.getAvatar(),
        a.getMonthlyCostLimit() == null ? null : a.getMonthlyCostLimit().doubleValue(),
        a.getParentAgentId(), List.of(a.getResponsibilities()), a.getHandsOffTo(),
        a.isMemoryFromManager(), a.getOriginTemplateId(), a.getOriginTemplateVersion());
  }
}
