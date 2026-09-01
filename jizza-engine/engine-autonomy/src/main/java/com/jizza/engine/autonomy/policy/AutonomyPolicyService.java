package com.jizza.engine.autonomy.policy;

import com.jizza.engine.api.autonomy.AutonomyPolicyStore;
import com.jizza.engine.api.dto.AutonomyRuleDto;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.persistence.entity.AutonomyPolicyEntity;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage for the policy rows the gate reads (R41).
 *
 * <p>Policy-free in the product sense: it stores whatever rules it is given. What a sensible
 * default policy looks like, and what is worth recording in the activity log, are the platform's
 * decisions.
 */
@Service
public class AutonomyPolicyService implements AutonomyPolicyStore {

  private final AutonomyPolicyRepository policies;

  public AutonomyPolicyService(AutonomyPolicyRepository policies) {
    this.policies = policies;
  }

  @Override
  @Transactional(readOnly = true)
  public List<AutonomyRuleDto> list(UUID userId) {
    return policies.findByUserIdOrderByPrecedenceDesc(userId).stream()
        .map(AutonomyPolicyService::toDto).toList();
  }

  @Override
  @Transactional
  public AutonomyRuleDto upsert(UUID userId, AutonomyRuleDto rule) {
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
    return toDto(entity);
  }

  @Override
  @Transactional
  public void delete(UUID userId, UUID policyId) {
    policies.findById(policyId)
        .filter(policy -> policy.getUserId().equals(userId))
        .ifPresent(policies::delete);
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
