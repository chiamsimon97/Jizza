package com.jizza.common.notification;

/**
 * The user's response to a blocking item. {@code instruction} carries the free-form text for an
 * {@code instruction_required} intervention (R38).
 */
public record Answer(Kind kind, String instruction, String feedback) {

  public enum Kind { APPROVE, DENY, SUBMIT_INSTRUCTION }

  public static Answer approve() { return new Answer(Kind.APPROVE, null, null); }
  public static Answer deny(String feedback) { return new Answer(Kind.DENY, null, feedback); }
  public static Answer instruction(String text) { return new Answer(Kind.SUBMIT_INSTRUCTION, text, null); }
}
