package com.jizza.platform.usecase.autonomy;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.AutonomyPolicyStore;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.dto.AutonomyRuleDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing what agents may do unattended (R40/R41).
 *
 * <p>Every route into this is a security setting, so every change is written to the activity log --
 * a policy that quietly loosened without a trace would be worth more to an attacker than most of
 * the data behind it.
 */
@Service
public class AutonomyUseCase {

  private final AutonomyPolicyStore policies;
  private final KillSwitch killSwitch;
  private final AuditLog audit;

  public AutonomyUseCase(AutonomyPolicyStore policies, KillSwitch killSwitch, AuditLog audit) {
    this.policies = policies;
    this.killSwitch = killSwitch;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<AutonomyRuleDto> list(UUID userId) {
    return policies.list(userId);
  }

  @Transactional(readOnly = true)
  public boolean paused(UUID userId) {
    return killSwitch.paused(userId);
  }

  @Transactional
  public AutonomyRuleDto upsert(UUID userId, AutonomyRuleDto rule) {
    if (rule.rule() == null) throw new ValidationException("A policy needs a rule");

    AutonomyRuleDto saved = policies.upsert(userId, rule);
    audit.record(userId, ActorType.USER, null, "autonomy.policy_changed", saved.toolRef(),
        Json.obj().put("agentId", String.valueOf(saved.agentId()))
            .put("rule", saved.rule().name().toLowerCase(Locale.ROOT))
            .put("precedence", saved.precedence())
            .put("workflowId", saved.workflowId() == null ? null : saved.workflowId().toString())
            .put("workflowVersion", saved.workflowVersion())
            .put("nodeId", saved.nodeId())
            .put("locked", saved.locked())
            // The conditions themselves, so a loosened rule can be traced to exactly what it allows.
            .set("limits", saved.limits()));
    return saved;
  }

  @Transactional
  public void delete(UUID userId, UUID policyId) {
    policies.list(userId).stream()
        .filter(policy -> policy.id().equals(policyId))
        .findFirst()
        .ifPresent(policy -> {
          policies.delete(userId, policyId);
          audit.record(userId, ActorType.USER, null, "autonomy.policy_removed", policy.toolRef(),
              null);
        });
  }

  /**
   * The rule that applies when no row matches, phrased for the settings screen so the user is told
   * the truth rather than shown an empty list.
   */
  public String defaultRuleDescription() {
    return "Reading and keeping Jizza's own notes run automatically; anything that sends, pays, "
        + "creates, changes or deletes something outside Jizza asks you first. No agent moves "
        + "money unless you allow it.";
  }

  public void setPaused(UUID userId, boolean paused) {
    killSwitch.setPaused(userId, paused);
  }
}
