package com.jizza.engine.api.autonomy;

import com.jizza.engine.api.dto.AutonomyRuleDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the autonomy rules the gate reads (R41).
 *
 * <p>Holds no opinion about what a sensible policy is. Note that an account with no rows at all is
 * not permissive: {@link AutonomyGate} falls back to the conservative default in code, so deleting
 * every rule makes the system safer rather than looser.
 */
public interface AutonomyPolicyStore {

  List<AutonomyRuleDto> list(UUID userId);

  AutonomyRuleDto upsert(UUID userId, AutonomyRuleDto rule);

  void delete(UUID userId, UUID policyId);

  /** One rule, only if it belongs to this user. */
  default Optional<AutonomyRuleDto> get(UUID userId, UUID policyId) {
    return list(userId).stream().filter(rule -> policyId.equals(rule.id())).findFirst();
  }

  /**
   * An organization's locked ceilings. Who may set them is the caller's decision; the store only
   * keeps them apart from anyone's personal rules.
   */
  default List<AutonomyRuleDto> listForWorkspace(UUID workspaceId) {
    return List.of();
  }

  /** Saves an organization ceiling, recorded as set by {@code userId}. */
  default AutonomyRuleDto upsertForWorkspace(UUID userId, UUID workspaceId, AutonomyRuleDto rule) {
    throw new UnsupportedOperationException("Organization guardrails are not supported");
  }

  default void deleteForWorkspace(UUID workspaceId, UUID policyId) {
    throw new UnsupportedOperationException("Organization guardrails are not supported");
  }
}
