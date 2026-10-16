package com.jizza.engine.api.enums;

/**
 * What a guardrail says about an action. Shown to people as Auto, Ask me, Let Jizza decide and
 * Never.
 */
public enum AutonomyRule {
  /** Auto: runs without asking. */
  ALLOW,
  /** Ask me: waits for the user's approval. */
  ASK,
  /** Let Jizza decide: Jizza approves it against the user's own instructions, or asks the user. */
  JIZZA,
  /** Never: not run; the agent reports what it wanted to do instead. */
  DENY;

  /** How strict a rule is. A ceiling can only ever raise this, never lower it. */
  public int strictness() {
    return switch (this) {
      case ALLOW -> 0;
      case JIZZA -> 1;
      case ASK -> 2;
      case DENY -> 3;
    };
  }

  public static AutonomyRule stricter(AutonomyRule a, AutonomyRule b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.strictness() >= b.strictness() ? a : b;
  }
}
