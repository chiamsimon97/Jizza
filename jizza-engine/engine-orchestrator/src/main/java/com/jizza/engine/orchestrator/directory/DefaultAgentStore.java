package com.jizza.engine.orchestrator.directory;

import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.orchestrator.AgentRevision;
import com.jizza.engine.api.orchestrator.AgentSpec;
import com.jizza.engine.api.orchestrator.AgentStore;
import com.jizza.engine.api.orchestrator.CustomAgentDraft;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.AgentRevisionEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.AgentRevisionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes to the agent roster (R21).
 *
 * <p>Does what it is told and nothing more: which agents an account gets, what a valid name looks
 * like and which agents must stay enabled are product rules and live in the platform. The one
 * invariant kept here is that a draft or archived agent is never enabled, so every query that
 * looks for enabled agents (rosters, workflow plans, the gate) leaves them out without knowing
 * about statuses.
 */
@Service
public class DefaultAgentStore implements AgentStore {

  private final AgentRepository agents;
  private final AgentRevisionRepository revisions;

  public DefaultAgentStore(AgentRepository agents, AgentRevisionRepository revisions) {
    this.agents = agents;
    this.revisions = revisions;
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
          agent.setDescription(spec.description());
          agent.setEnabled(true);
          // Specialists report to Jizza, which is always provisioned first.
          if (!spec.orchestrator()) {
            agents.findByUserIdAndIsOrchestratorTrue(userId)
                .ifPresent(jizza -> agent.setParentAgentId(jizza.getId()));
          }
          return DefaultAgentDirectory.toDto(agents.save(agent));
        });
  }

  @Override
  @Transactional
  public AgentDto rename(UUID agentId, String name) {
    AgentEntity agent = require(agentId);
    agent.setName(name);
    return save(agent);
  }

  @Override
  @Transactional
  public AgentDto setEnabled(UUID agentId, boolean enabled) {
    AgentEntity agent = require(agentId);
    agent.setEnabled(enabled && agent.isActive());
    return save(agent);
  }

  @Override
  @Transactional
  public AgentDto createCustom(UUID userId, String key, CustomAgentDraft draft) {
    AgentEntity agent = new AgentEntity();
    agent.setUserId(userId);
    agent.setKey(key);
    // Its own memory domain, so what it learns stays with it unless shared.
    agent.setDomain(key);
    agent.setCustom(true);
    agent.setOrchestrator(false);
    agent.setCreatedAt(Instant.now());
    apply(agent, draft);
    agent.setEnabled(agent.isActive());
    return save(agent);
  }

  @Override
  @Transactional
  public AgentDto updateCustom(UUID agentId, CustomAgentDraft draft) {
    AgentEntity agent = require(agentId);
    boolean wasEnabled = agent.isEnabled();
    apply(agent, draft);
    agent.setEnabled(wasEnabled && agent.isActive());
    return save(agent);
  }

  @Override
  @Transactional
  public AgentDto setStatus(UUID agentId, String status) {
    AgentEntity agent = require(agentId);
    agent.setStatus(status);
    agent.setEnabled(agent.isActive());
    return save(agent);
  }

  @Override
  @Transactional
  public AgentDto setParent(UUID agentId, UUID parentAgentId) {
    AgentEntity agent = require(agentId);
    agent.setParentAgentId(parentAgentId);
    return save(agent);
  }

  @Override
  @Transactional
  public void delete(UUID agentId) {
    agents.deleteById(agentId);
  }

  @Override
  @Transactional
  public AgentRevision recordRevision(UUID agentId, UUID changedByUserId) {
    AgentEntity agent = require(agentId);
    int next = revisions.findFirstByAgentIdOrderByRevisionDesc(agentId)
        .map(r -> r.getRevision() + 1).orElse(1);
    AgentRevisionEntity revision = new AgentRevisionEntity();
    revision.setAgentId(agentId);
    revision.setRevision(next);
    revision.setName(agent.getName());
    revision.setDescription(agent.getDescription());
    revision.setSystemPrompt(agent.getSystemPrompt());
    revision.setModelTier(agent.getModelTier());
    revision.setChangedByUserId(changedByUserId);
    return toRevision(revisions.save(revision));
  }

  @Override
  @Transactional(readOnly = true)
  public List<AgentRevision> revisions(UUID agentId) {
    return revisions.findByAgentIdOrderByRevisionDesc(agentId).stream()
        .map(DefaultAgentStore::toRevision).toList();
  }

  private static void apply(AgentEntity agent, CustomAgentDraft draft) {
    agent.setName(draft.name());
    agent.setDescription(draft.description());
    agent.setSystemPrompt(draft.systemPrompt() == null ? "" : draft.systemPrompt());
    agent.setModelTier(draft.modelTier() == null ? "standard" : draft.modelTier());
    agent.setMemoryDomains(draft.memoryDomains() == null ? new String[0]
        : draft.memoryDomains().toArray(String[]::new));
    agent.setAvatar(draft.avatar());
    agent.setMonthlyCostLimit(draft.monthlyCostLimit() == null ? null
        : BigDecimal.valueOf(draft.monthlyCostLimit()));
    agent.setParentAgentId(draft.parentAgentId());
    agent.setResponsibilities(draft.responsibilities() == null ? new String[0]
        : draft.responsibilities().toArray(String[]::new));
    agent.setHandsOffTo(draft.handsOffTo());
    agent.setMemoryFromManager(draft.memoryFromManager());
    agent.setStatus(draft.status() == null ? "active" : draft.status());
    agent.setOriginTemplateId(draft.originTemplateId());
    agent.setOriginTemplateVersion(draft.originTemplateVersion());
  }

  private AgentDto save(AgentEntity agent) {
    agent.setUpdatedAt(Instant.now());
    return DefaultAgentDirectory.toDto(agents.save(agent));
  }

  private static AgentRevision toRevision(AgentRevisionEntity r) {
    return new AgentRevision(r.getId(), r.getAgentId(), r.getRevision(), r.getName(),
        r.getDescription(), r.getSystemPrompt(), r.getModelTier(), r.getChangedByUserId(),
        r.getCreatedAt());
  }

  private AgentEntity require(UUID agentId) {
    return agents.findById(agentId).orElseThrow(() -> new NotFoundException("Agent", agentId));
  }
}
