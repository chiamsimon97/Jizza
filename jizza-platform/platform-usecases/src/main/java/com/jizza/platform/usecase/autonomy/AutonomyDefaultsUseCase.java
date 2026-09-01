package com.jizza.platform.usecase.autonomy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.AutonomyPolicyStore;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.dto.AutonomyRuleDto;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.event.UserRegistered;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The autonomy policy a new account starts with (R41).
 *
 * <p>These rows say out loud what the engine would do anyway, so the settings screen shows the user
 * the actual policy rather than an empty list they have to take on trust. Deleting them does not
 * loosen anything: with no rows at all the gate falls back to the same conservative rule in code.
 *
 * <p>Which rules a new account gets is a product decision, which is why it lives here. Runs after
 * the team has been provisioned, because the per-agent rules need agents to attach to.
 */
@Service
public class AutonomyDefaultsUseCase {

  private static final Logger log = LoggerFactory.getLogger(AutonomyDefaultsUseCase.class);

  private final AutonomyPolicyStore policies;
  private final AgentDirectory agents;

  public AutonomyDefaultsUseCase(AutonomyPolicyStore policies, AgentDirectory agents) {
    this.policies = policies;
    this.agents = agents;
  }

  @EventListener
  @Order(100)
  @Transactional
  public void onUserRegistered(UserRegistered event) {
    seedFor(event.userId());
  }

  /** Idempotent: an account that already has any policy keeps what it has. */
  @Transactional
  public void seedFor(UUID userId) {
    if (!policies.list(userId).isEmpty()) return;

    policy(userId, null, AutonomyGate.ANY_READ, AutonomyRule.ALLOW, 0, null);
    policy(userId, null, AutonomyGate.ANY_SIDE_EFFECTING, AutonomyRule.ASK, 0, null);

    // Finance may read freely but never move money unattended, whatever else is configured.
    agents.byKey(userId, "finance").ifPresent(finance -> {
      policy(userId, finance.id(), AutonomyGate.ANY_READ, AutonomyRule.ALLOW, 10, null);
      policy(userId, finance.id(), AutonomyGate.ANY_SIDE_EFFECTING, AutonomyRule.ASK, 10,
          Json.obj().put("note", "Money movement always asks"));
    });
    // Health and Professional may read and draft unattended; sending still asks.
    for (String key : new String[] {"health", "professional"}) {
      agents.byKey(userId, key).map(AgentDto::id).ifPresent(agentId -> {
        policy(userId, agentId, AutonomyGate.ANY_READ, AutonomyRule.ALLOW, 10, null);
        policy(userId, agentId, AutonomyGate.ANY_SIDE_EFFECTING, AutonomyRule.ASK, 10, null);
      });
    }
    log.info("Seeded conservative autonomy defaults for user {}", userId);
  }

  private void policy(UUID userId, UUID agentId, String toolRef, AutonomyRule rule, int precedence,
                      JsonNode limits) {
    policies.upsert(userId,
        new AutonomyRuleDto(null, userId, agentId, toolRef, rule, limits, precedence));
  }
}
