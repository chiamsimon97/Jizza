package com.jizza.common.autonomy;

import com.jizza.common.enums.AutonomyRule;

/**
 * Outcome of an autonomy evaluation. {@code ASK} means the action must be routed through the
 * permission gate rather than executed.
 */
public record Decision(AutonomyRule rule, String reason, String matchedPolicy) {

  public static Decision allow(String matched) { return new Decision(AutonomyRule.ALLOW, "allowed by policy", matched); }
  public static Decision ask(String reason, String matched) { return new Decision(AutonomyRule.ASK, reason, matched); }
  public static Decision deny(String reason, String matched) { return new Decision(AutonomyRule.DENY, reason, matched); }

  public boolean allowed() { return rule == AutonomyRule.ALLOW; }
  public boolean needsPermission() { return rule == AutonomyRule.ASK; }
  public boolean denied() { return rule == AutonomyRule.DENY; }
}
