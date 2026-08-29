package com.jizza.engine.orchestrator.directory;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rename and enable/disable (R21, R40). The orchestrator itself can be renamed but not disabled. */
@Service
public class AgentAdminService {

  private final AgentRepository agents;
  private final AuditLog audit;

  public AgentAdminService(AgentRepository agents, AuditLog audit) {
    this.agents = agents;
    this.audit = audit;
  }

  @Transactional
  public AgentDto rename(UUID agentId, String name) {
    if (name == null || name.isBlank()) throw new ValidationException("An agent needs a name");
    if (name.length() > 60) throw new ValidationException("Agent names are limited to 60 characters");

    AgentEntity agent = require(agentId);
    String previous = agent.getName();
    agent.setName(name.trim());
    agents.save(agent);
    audit.record(agent.getUserId(), ActorType.USER, null, "agent.renamed", agent.getKey(),
        Json.obj().put("from", previous).put("to", agent.getName()));
    return DefaultAgentDirectory.toDto(agent);
  }

  @Transactional
  public AgentDto setEnabled(UUID agentId, boolean enabled) {
    AgentEntity agent = require(agentId);
    if (agent.isOrchestrator() && !enabled) {
      // Disabling Jizza would leave the user with nobody to talk to, which is not a state the
      // system should be able to enter.
      throw new ValidationException("Jizza is the only way you reach your agents and cannot be "
          + "disabled. Use the kill switch to pause autonomous activity instead.");
    }
    agent.setEnabled(enabled);
    agents.save(agent);
    audit.record(agent.getUserId(), ActorType.USER, null,
        enabled ? "agent.enabled" : "agent.disabled", agent.getKey(), null);
    return DefaultAgentDirectory.toDto(agent);
  }

  private AgentEntity require(UUID agentId) {
    return agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent", agentId));
  }
}
