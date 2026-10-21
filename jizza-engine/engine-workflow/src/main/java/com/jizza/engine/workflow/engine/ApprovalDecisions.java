package com.jizza.engine.workflow.engine;

/**
 * Where an approval step keeps the user's answer: in its own state, so running the step again (the
 * engine does, once answered) reads it back and takes the matching path.
 */
public final class ApprovalDecisions {

  private ApprovalDecisions() {}

  public static void record(RunContext context, String nodeId, String decision, String comment) {
    var state = context.state(nodeId);
    state.put("decision", decision);
    if (comment != null && !comment.isBlank()) state.put("comment", comment);
  }

  /** "approved", "rejected", or null while nobody has answered. */
  public static String of(RunContext context, String nodeId) {
    var value = context.state(nodeId).path("decision");
    return value.isTextual() ? value.asText() : null;
  }
}
