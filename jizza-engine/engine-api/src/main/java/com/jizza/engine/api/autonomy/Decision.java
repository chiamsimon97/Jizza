package com.jizza.engine.api.autonomy;

import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.AutonomyRule;
import java.util.UUID;

/**
 * Outcome of a guardrail evaluation. {@code ASK} means the action must be routed through the
 * permission gate rather than executed; {@code JIZZA} means Jizza reviews it first and, when it
 * cannot clearly approve, asks the user.
 *
 * @param matchedPolicy a readable label of the rule that decided, for the activity and audit log
 * @param policyId      the rule that decided, or null when the built-in default did
 * @param actionType    what the tool does, as the gate classified it
 * @param budgeted      true when allowing this call spends from a rule's budget or call count
 * @param jizzaActs     true when the person chose Let Jizza decide but Jizza is the agent acting,
 *                      so the call asks instead (Jizza never approves its own actions). A step
 *                      that only matches a named item against a list on the page still honours
 *                      the choice, since that is a plain match and not Jizza's own judgement.
 */
public record Decision(AutonomyRule rule, String reason, String matchedPolicy, UUID policyId,
                       ActionType actionType, boolean budgeted, boolean jizzaActs) {

  public Decision(AutonomyRule rule, String reason, String matchedPolicy, UUID policyId,
                  ActionType actionType, boolean budgeted) {
    this(rule, reason, matchedPolicy, policyId, actionType, budgeted, false);
  }

  public Decision(AutonomyRule rule, String reason, String matchedPolicy) {
    this(rule, reason, matchedPolicy, null, null, false);
  }

  public static Decision allow(String matched) { return new Decision(AutonomyRule.ALLOW, "allowed by policy", matched); }
  public static Decision ask(String reason, String matched) { return new Decision(AutonomyRule.ASK, reason, matched); }
  public static Decision deny(String reason, String matched) { return new Decision(AutonomyRule.DENY, reason, matched); }

  public boolean allowed() { return rule == AutonomyRule.ALLOW; }
  public boolean needsPermission() { return rule == AutonomyRule.ASK; }
  public boolean needsJizza() { return rule == AutonomyRule.JIZZA; }
  public boolean denied() { return rule == AutonomyRule.DENY; }

  public Decision withRule(AutonomyRule newRule, String newReason) {
    return new Decision(newRule, newReason, matchedPolicy, policyId, actionType, false);
  }

  /** Let Jizza decide, asked instead because Jizza is the agent acting. */
  public Decision askedBecauseJizzaActs(String newReason) {
    return new Decision(AutonomyRule.ASK, newReason, matchedPolicy, policyId, actionType, false,
        true);
  }

  public Decision withMatched(String label) {
    return new Decision(rule, reason, label, policyId, actionType, budgeted, jizzaActs);
  }
}
