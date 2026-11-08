package com.jizza.engine.api.notification;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The user's response to a blocking item. {@code instruction} carries the free-form text for an
 * {@code instruction_required} intervention (R38). {@code inputs}, on an approval, carries the
 * values the person set on the approval card of a workflow step (a value they changed, or a
 * mandatory one that was empty), keyed as the card's fields are.
 */
public record Answer(Kind kind, String instruction, String feedback, JsonNode inputs) {

  public Answer(Kind kind, String instruction, String feedback) {
    this(kind, instruction, feedback, null);
  }

  public enum Kind { APPROVE, DENY, SUBMIT_INSTRUCTION }

  public static Answer approve() { return new Answer(Kind.APPROVE, null, null); }
  public static Answer deny(String feedback) { return new Answer(Kind.DENY, null, feedback); }
  public static Answer instruction(String text) { return new Answer(Kind.SUBMIT_INSTRUCTION, text, null); }
}
