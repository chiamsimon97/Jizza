package com.jizza.engine.autonomy.policy;

import com.jizza.engine.api.autonomy.AutonomyPolicyStore;
import com.jizza.engine.api.dto.AutonomyRuleDto;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.autonomy.guardrail.GuardrailConditions;
import com.jizza.engine.persistence.entity.AutonomyPolicyEntity;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import java.util.List;
import java.util.Optional;
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
  private final GuardrailConditions conditions;

  public AutonomyPolicyService(AutonomyPolicyRepository policies, GuardrailConditions conditions) {
    this.policies = policies;
    this.conditions = conditions;
  }

  @Override
  @Transactional(readOnly = true)
  public List<AutonomyRuleDto> list(UUID userId) {
    return policies.findByUserIdOrderByPrecedenceDesc(userId).stream()
        .filter(policy -> policy.getWorkspaceId() == null)
        .map(AutonomyPolicyService::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<AutonomyRuleDto> listForWorkspace(UUID workspaceId) {
    return policies.findByWorkspaceId(workspaceId).stream()
        .map(AutonomyPolicyService::toDto).toList();
  }

  @Override
  @Transactional
  public AutonomyRuleDto upsertForWorkspace(UUID userId, UUID workspaceId, AutonomyRuleDto rule) {
    if (rule.rule() == null) throw new ValidationException("A guardrail needs a mode");
    if (rule.rule() == AutonomyRule.ALLOW || rule.rule() == AutonomyRule.JIZZA) {
      // A ceiling only ever makes things stricter, so a looser one would do nothing.
      throw new ValidationException("An organization guardrail can only be Ask me or Never");
    }
    if (rule.limits() != null && !rule.limits().isNull()) {
      throw new ValidationException("An organization guardrail has no conditions");
    }
    AutonomyPolicyEntity entity = rule.id() == null
        ? new AutonomyPolicyEntity()
        : policies.findById(rule.id())
            .filter(existing -> workspaceId.equals(existing.getWorkspaceId()))
            .orElseThrow(() -> new NotFoundException("Autonomy policy", rule.id()));
    if (entity.getUserId() == null) entity.setUserId(userId);
    entity.setWorkspaceId(workspaceId);
    entity.setAgentId(null);
    entity.setWorkflowId(null);
    entity.setWorkflowVersion(null);
    entity.setNodeId(null);
    entity.setToolRef(rule.toolRef() == null || rule.toolRef().isBlank()
        ? PolicyAutonomyGate.ANY : rule.toolRef().trim());
    entity.setRule(rule.rule());
    entity.setLimits(null);
    entity.setPrecedence(rule.precedence());
    entity.setLocked(true);
    policies.save(entity);
    return toDto(entity);
  }

  @Override
  @Transactional
  public void deleteForWorkspace(UUID workspaceId, UUID policyId) {
    policies.findById(policyId)
        .filter(policy -> workspaceId.equals(policy.getWorkspaceId()))
        .ifPresent(policies::delete);
  }

  @Override
  @Transactional
  public AutonomyRuleDto upsert(UUID userId, AutonomyRuleDto rule) {
    if (rule.rule() == null) throw new ValidationException("A guardrail needs a mode");
    if (rule.nodeId() != null && rule.workflowId() == null) {
      throw new ValidationException("A rule for one step must name its workflow");
    }
    if (rule.workflowVersion() != null && rule.workflowId() == null) {
      throw new ValidationException("A rule for one workflow version must name its workflow");
    }
    conditions.validate(rule.limits());

    AutonomyPolicyEntity entity = rule.id() == null
        ? new AutonomyPolicyEntity()
        : policies.findById(rule.id())
            // Someone else's rule is reported as missing, never quietly taken over.
            .filter(existing -> existing.getUserId().equals(userId)
                && existing.getWorkspaceId() == null)
            .orElseThrow(() -> new NotFoundException("Autonomy policy", rule.id()));
    entity.setUserId(userId);
    entity.setAgentId(rule.agentId());
    entity.setToolRef(rule.toolRef() == null || rule.toolRef().isBlank()
        ? PolicyAutonomyGate.ANY : rule.toolRef().trim());
    entity.setRule(rule.rule());
    entity.setLimits(rule.limits() == null || rule.limits().isNull() ? null : rule.limits());
    entity.setPrecedence(rule.precedence());
    entity.setWorkflowId(rule.workflowId());
    entity.setWorkflowVersion(rule.workflowVersion());
    entity.setNodeId(rule.nodeId() == null || rule.nodeId().isBlank() ? null : rule.nodeId());
    entity.setLocked(rule.locked());
    policies.save(entity);
    return toDto(entity);
  }

  @Override
  @Transactional
  public void delete(UUID userId, UUID policyId) {
    policies.findById(policyId)
        .filter(policy -> policy.getUserId().equals(userId) && policy.getWorkspaceId() == null)
        .ifPresent(policies::delete);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AutonomyRuleDto> get(UUID userId, UUID policyId) {
    return policies.findById(policyId)
        .filter(policy -> policy.getUserId().equals(userId) && policy.getWorkspaceId() == null)
        .map(AutonomyPolicyService::toDto);
  }

  public static AutonomyRuleDto toDto(AutonomyPolicyEntity p) {
    return new AutonomyRuleDto(p.getId(), p.getUserId(), p.getAgentId(), p.getToolRef(),
        p.getRule(), p.getLimits(), p.getPrecedence(), p.getWorkflowId(), p.getWorkflowVersion(),
        p.getNodeId(), p.isLocked(), p.getWorkspaceId());
  }

  /** The rule that applies when no row matches, exposed so the UI can show it honestly. */
  public static String defaultRuleDescription() {
    return "Reading and keeping Jizza's own notes run automatically; anything that sends, pays, "
        + "creates, changes or deletes something outside Jizza asks you first.";
  }

  public static AutonomyRule defaultFor(boolean sideEffecting) {
    return sideEffecting ? AutonomyRule.ASK : AutonomyRule.ALLOW;
  }
}
