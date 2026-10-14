package com.jizza.engine.api.cosmos;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The run an agent is reading memory in: a chat turn, a scheduled task, a workflow run. Lets a read
 * stamp say which run it was, and lets a run that reads the same item many times stamp it once.
 *
 * <p>Carried as {@code AgentRequest.traceId} ({@code "kind:ref"}) across the places a run starts,
 * and held on the thread for the duration of {@code BaseAgent.run}, so delegation can pass it on.
 */
public record AccessContext(String kind, String ref) {

  public static final String CHAT_TURN = "chat_turn";
  public static final String TASK = "task";
  public static final String WORKFLOW_RUN = "workflow_run";
  public static final String AGENT_RUN = "agent_run";

  private static final ThreadLocal<AccessContext> CURRENT = new ThreadLocal<>();

  public static AccessContext chatTurn(UUID messageId) { return new AccessContext(CHAT_TURN, messageId.toString()); }

  public static AccessContext task(UUID taskId) { return new AccessContext(TASK, taskId.toString()); }

  public static Optional<AccessContext> current() { return Optional.ofNullable(CURRENT.get()); }

  /**
   * The context a trace id names. A bare id is a workflow run's (the workflow engine passes its run
   * id); anything else unparseable starts a fresh agent run.
   */
  public static AccessContext fromTrace(String traceId) {
    if (traceId == null || traceId.isBlank()) return new AccessContext(AGENT_RUN, UUID.randomUUID().toString());
    int colon = traceId.indexOf(':');
    if (colon > 0) {
      String kind = traceId.substring(0, colon);
      if (kind.equals(CHAT_TURN) || kind.equals(TASK) || kind.equals(WORKFLOW_RUN) || kind.equals(AGENT_RUN)) {
        return new AccessContext(kind, traceId.substring(colon + 1));
      }
    }
    return new AccessContext(WORKFLOW_RUN, traceId);
  }

  public String toTrace() { return kind + ":" + ref; }

  /** Runs {@code work} with this context current on the thread, restoring whatever was there. */
  public <T> T run(Supplier<T> work) {
    AccessContext previous = CURRENT.get();
    CURRENT.set(this);
    try {
      return work.get();
    } finally {
      if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
  }
}
