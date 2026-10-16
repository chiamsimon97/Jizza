package com.jizza.engine.api.autonomy;

import java.util.UUID;

/**
 * Decides an action a guardrail left to Jizza. It may approve only by naming one of the user's own
 * confirmed standing instructions that covers the action; anything else is an {@code ask}. It never
 * denies on its own and never changes a rule.
 */
public interface JizzaReviewer {

  /**
   * @param actingAgentId the specialist that wants to act
   * @param reason        what the guardrail said, for the record
   */
  Verdict review(UUID userId, UUID actingAgentId, GuardrailContext context, Decision decision);

  /**
   * @param approved      true only when {@code instructionId} names an instruction that covers it
   * @param instructionId the standing instruction Jizza relied on
   * @param explanation   one sentence for the activity log
   */
  record Verdict(boolean approved, UUID instructionId, String instructionText, String explanation) {

    public static Verdict ask(String explanation) {
      return new Verdict(false, null, null, explanation);
    }
  }
}
