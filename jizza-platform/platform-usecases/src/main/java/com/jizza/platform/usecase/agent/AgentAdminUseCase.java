package com.jizza.platform.usecase.agent;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.orchestrator.AgentStore;
import com.jizza.engine.api.util.Json;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the user may change about their agents (R21, R40): rename them, and turn specialists off.
 *
 * <p>The one rule worth stating out loud is that Jizza itself cannot be disabled. It is the only
 * way the user reaches any of this, so switching it off would leave them with nobody to talk to.
 * Pausing autonomous activity is what the kill switch is for, and that is a separate control.
 */
@Service
public class AgentAdminUseCase {

  private static final int MAX_NAME_LENGTH = 60;

  private final AgentStore agents;
  private final AgentDirectory directory;
  private final AuditLog audit;

  public AgentAdminUseCase(AgentStore agents, AgentDirectory directory, AuditLog audit) {
    this.agents = agents;
    this.directory = directory;
    this.audit = audit;
  }

  @Transactional
  public AgentDto rename(UUID userId, UUID agentId, String name) {
    AgentDto agent = ownedBy(userId, agentId);
    if (name == null || name.isBlank()) {
      throw new ValidationException("An agent needs a name");
    }
    if (name.trim().length() > MAX_NAME_LENGTH) {
      throw new ValidationException("Agent names are limited to " + MAX_NAME_LENGTH + " characters");
    }

    String trimmed = name.trim();
    if (!agent.orchestrator() && trimmed.equalsIgnoreCase("jizza")) {
      throw new ValidationException("Only your assistant is called Jizza");
    }
    boolean taken = directory.list(userId).stream()
        .filter(a -> !a.id().equals(agentId) && !"archived".equals(a.status()))
        .anyMatch(a -> a.name().equalsIgnoreCase(trimmed));
    if (taken) throw new ValidationException("You already have an agent called " + trimmed);

    AgentDto renamed = agents.rename(agentId, trimmed);
    audit.record(userId, ActorType.USER, null, "agent.renamed", agent.key(),
        Json.obj().put("from", agent.name()).put("to", renamed.name()));
    return renamed;
  }

  @Transactional
  public AgentDto setEnabled(UUID userId, UUID agentId, boolean enabled) {
    AgentDto agent = ownedBy(userId, agentId);
    if (agent.orchestrator() && !enabled) {
      throw new ValidationException("Jizza is the only way you reach your agents and cannot be "
          + "disabled. Use the kill switch to pause autonomous activity instead.");
    }

    if (enabled && !agent.active()) {
      throw new ValidationException("draft".equals(agent.status())
          ? "Finish and activate " + agent.name() + " first"
          : "Restore " + agent.name() + " from the archive first");
    }

    AgentDto updated = agents.setEnabled(agentId, enabled);
    audit.record(userId, ActorType.USER, null, enabled ? "agent.enabled" : "agent.disabled",
        agent.key(), null);
    return updated;
  }

  /**
   * Whether the agent may search the web and read pages, and whether web tools are switched on for
   * this server at all (when they are not, the setting is kept but has no effect).
   */
  public record WebAccess(boolean enabled, boolean available) {}

  @org.springframework.beans.factory.annotation.Value("${jizza.agents.anthropic.web.enabled:true}")
  private boolean webAvailable = true;

  @Transactional(readOnly = true)
  public WebAccess webAccess(UUID userId, UUID agentId) {
    ownedBy(userId, agentId);
    return new WebAccess(agents.webAccess(agentId), webAvailable);
  }

  @Transactional
  public WebAccess setWebAccess(UUID userId, UUID agentId, boolean on) {
    AgentDto agent = ownedBy(userId, agentId);
    agents.setWebAccess(agentId, on);
    audit.record(userId, ActorType.USER, null, on ? "agent.web.on" : "agent.web.off", agent.key(), null);
    return new WebAccess(on, webAvailable);
  }

  public AgentDto ownedBy(UUID userId, UUID agentId) {
    return directory.byId(agentId)
        .filter(agent -> agent.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Agent", agentId));
  }
}
