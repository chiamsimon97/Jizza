package com.jizza.engine.orchestrator.directory;

import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.orchestrator.AgentSpec;
import com.jizza.engine.api.orchestrator.AgentStore;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes to the agent roster (R21).
 *
 * <p>Does what it is told and nothing more: which agents an account gets, what a valid name looks
 * like and which agents must stay enabled are product rules and live in the platform.
 */
@Service
public class DefaultAgentStore implements AgentStore {

  private final AgentRepository agents;

  public DefaultAgentStore(AgentRepository agents) {
    this.agents = agents;
  }

  /** Idempotent by key, so provisioning an account twice does not duplicate its team. */
  @Override
  @Transactional
  public AgentDto createIfAbsent(UUID userId, AgentSpec spec) {
    return agents.findByUserIdAndKey(userId, spec.key())
        .map(DefaultAgentDirectory::toDto)
        .orElseGet(() -> {
          AgentEntity agent = new AgentEntity();
          agent.setUserId(userId);
          agent.setKey(spec.key());
          agent.setName(spec.name());
          agent.setDomain(spec.domain());
          agent.setSystemPrompt(spec.systemPrompt());
          agent.setOrchestrator(spec.orchestrator());
          agent.setEnabled(true);
          return DefaultAgentDirectory.toDto(agents.save(agent));
        });
  }

  @Override
  @Transactional
  public AgentDto rename(UUID agentId, String name) {
    AgentEntity agent = require(agentId);
    agent.setName(name);
    return DefaultAgentDirectory.toDto(agents.save(agent));
  }

  @Override
  @Transactional
  public AgentDto setEnabled(UUID agentId, boolean enabled) {
    AgentEntity agent = require(agentId);
    agent.setEnabled(enabled);
    return DefaultAgentDirectory.toDto(agents.save(agent));
  }

  private AgentEntity require(UUID agentId) {
    return agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent", agentId));
  }
}
