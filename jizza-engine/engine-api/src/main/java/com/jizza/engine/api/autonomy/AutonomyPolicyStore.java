package com.jizza.engine.api.autonomy;

import com.jizza.engine.api.dto.AutonomyRuleDto;
import java.util.List;
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
  default java.util.Optional<AutonomyRuleDto> get(UUID userId, UUID policyId) {
    return list(userId).stream().filter(rule -> policyId.equals(rule.id())).findFirst();
  }
}
