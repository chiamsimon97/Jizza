package com.jizza.engine.autonomy.policy;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.AutonomyRuleDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AutonomyPolicyEntity;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD over the policy rows. Every change is audited: this is a security setting (R14). */
@Service
public class AutonomyPolicyService {

  private final AutonomyPolicyRepository policies;
  private final AuditLog audit;

  public AutonomyPolicyService(AutonomyPolicyRepository policies, AuditLog audit) {
    this.policies = policies;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<AutonomyRuleDto> list(UUID userId) {
    return policies.findByUserIdOrderByPrecedenceDesc(userId).stream()
        .map(AutonomyPolicyService::toDto).toList();
  }

  @Transactional
  public AutonomyRuleDto upsert(UUID userId, AutonomyRuleDto rule) {
    if (rule.rule() == null) throw new ValidationException("A policy needs a rule");

    AutonomyPolicyEntity entity = rule.id() == null
        ? new AutonomyPolicyEntity()
        : policies.findById(rule.id())
            .orElseThrow(() -> new NotFoundException("Autonomy policy", rule.id()));
    entity.setUserId(userId);
    entity.setAgentId(rule.agentId());
    entity.setToolRef(rule.toolRef() == null || rule.toolRef().isBlank()
        ? PolicyAutonomyGate.ANY : rule.toolRef());
    entity.setRule(rule.rule());
    entity.setLimits(rule.limits());
    entity.setPrecedence(rule.precedence());
    policies.save(entity);

    audit.record(userId, ActorType.USER, null, "autonomy.policy_changed", entity.getToolRef(),
        Json.obj().put("agentId", String.valueOf(entity.getAgentId()))
            .put("rule", entity.getRule().name().toLowerCase(java.util.Locale.ROOT))
            .put("precedence", entity.getPrecedence()));
    return toDto(entity);
  }

  @Transactional
  public void delete(UUID userId, UUID policyId) {
    policies.findById(policyId)
        .filter(policy -> policy.getUserId().equals(userId))
        .ifPresent(policy -> {
          policies.delete(policy);
          audit.record(userId, ActorType.USER, null, "autonomy.policy_removed",
              policy.getToolRef(), null);
        });
  }

  public static AutonomyRuleDto toDto(AutonomyPolicyEntity p) {
    return new AutonomyRuleDto(p.getId(), p.getUserId(), p.getAgentId(), p.getToolRef(),
        p.getRule(), p.getLimits(), p.getPrecedence());
  }

  /** The rule that applies when no row matches, exposed so the UI can show it honestly. */
  public static String defaultRuleDescription() {
    return "Reading and informing run automatically; anything that sends, pays, creates, deletes "
        + "or otherwise changes something outside Jizza asks you first.";
  }

  public static AutonomyRule defaultFor(boolean sideEffecting) {
    return sideEffecting ? AutonomyRule.ASK : AutonomyRule.ALLOW;
  }
}
