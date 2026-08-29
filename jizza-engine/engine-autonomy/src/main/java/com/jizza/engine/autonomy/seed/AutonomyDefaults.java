package com.jizza.engine.autonomy.seed;

import com.jizza.engine.autonomy.policy.PolicyAutonomyGate;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.event.UserRegistered;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AutonomyPolicyEntity;
import com.jizza.engine.persistence.entity.KillSwitchEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import com.jizza.engine.persistence.repository.KillSwitchRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the Charter's conservative autonomy defaults for a new account (R41).
 *
 * <p>These rows say out loud what the code would do anyway, so the settings screen shows the user
 * the actual policy rather than an empty list they have to take on trust. Deleting them does not
 * loosen anything: the code default is the same conservative rule.
 */
@Component
public class AutonomyDefaults {

  private static final Logger log = LoggerFactory.getLogger(AutonomyDefaults.class);

  private final AutonomyPolicyRepository policies;
  private final KillSwitchRepository killSwitch;
  private final AgentRepository agents;

  public AutonomyDefaults(AutonomyPolicyRepository policies, KillSwitchRepository killSwitch,
                          AgentRepository agents) {
    this.policies = policies;
    this.killSwitch = killSwitch;
    this.agents = agents;
  }

  /**
   * Runs after the agents have been seeded so the per-agent rules have agents to attach to; both
   * listeners are ordered on the same event.
   */
  @EventListener
  @org.springframework.core.annotation.Order(100)
  @Transactional
  public void onUserRegistered(UserRegistered event) {
    seedFor(event.userId());
  }

  @Transactional
  public void seedFor(UUID userId) {
    if (killSwitch.findById(userId).isEmpty()) {
      KillSwitchEntity entity = new KillSwitchEntity();
      entity.setUserId(userId);
      // Active by default: the switch exists to be thrown, not to start thrown.
      entity.setAutonomyPaused(false);
      killSwitch.save(entity);
    }

    if (!policies.findByUserIdOrderByPrecedenceDesc(userId).isEmpty()) return;

    policy(userId, null, PolicyAutonomyGate.ANY_READ, AutonomyRule.ALLOW, 0, null);
    policy(userId, null, PolicyAutonomyGate.ANY_SIDE_EFFECTING, AutonomyRule.ASK, 0, null);

    // Finance may read freely but never move money unattended, whatever else is configured.
    agents.findByUserIdAndKey(userId, "finance").ifPresent(finance -> {
      policy(userId, finance.getId(), PolicyAutonomyGate.ANY_READ, AutonomyRule.ALLOW, 10, null);
      policy(userId, finance.getId(), PolicyAutonomyGate.ANY_SIDE_EFFECTING, AutonomyRule.ASK, 10,
          Json.obj().put("note", "Money movement always asks"));
    });
    // Health and Professional may read and draft unattended; sending still asks.
    for (String key : new String[] {"health", "professional"}) {
      agents.findByUserIdAndKey(userId, key).ifPresent(agent -> {
        policy(userId, agent.getId(), PolicyAutonomyGate.ANY_READ, AutonomyRule.ALLOW, 10, null);
        policy(userId, agent.getId(), PolicyAutonomyGate.ANY_SIDE_EFFECTING, AutonomyRule.ASK, 10,
            null);
      });
    }
    log.info("Seeded conservative autonomy defaults for user {}", userId);
  }

  private void policy(UUID userId, UUID agentId, String toolRef, AutonomyRule rule, int precedence,
                      com.fasterxml.jackson.databind.JsonNode limits) {
    AutonomyPolicyEntity entity = new AutonomyPolicyEntity();
    entity.setUserId(userId);
    entity.setAgentId(agentId);
    entity.setToolRef(toolRef);
    entity.setRule(rule);
    entity.setPrecedence(precedence);
    entity.setLimits(limits);
    policies.save(entity);
  }
}
