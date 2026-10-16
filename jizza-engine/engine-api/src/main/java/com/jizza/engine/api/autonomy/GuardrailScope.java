package com.jizza.engine.api.autonomy;

import java.util.UUID;

/**
 * Which workflow step the current thread is running, so a tool call an agent makes during a
 * workflow's agent step is judged by that workflow's guardrails.
 *
 * <p>Losing the scope is always safe: a call made outside it is judged by the agent's own rules,
 * which are never looser than a workflow's.
 */
public final class GuardrailScope {

  public record Frame(UUID workflowId, int workflowVersion, String nodeId) {}

  private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

  private GuardrailScope() {}

  public static Frame current() {
    return CURRENT.get();
  }

  /** Opens a scope for the duration of a try-with-resources block. */
  public static Handle open(UUID workflowId, int workflowVersion, String nodeId) {
    Frame previous = CURRENT.get();
    CURRENT.set(new Frame(workflowId, workflowVersion, nodeId));
    return () -> {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    };
  }

  public interface Handle extends AutoCloseable {
    @Override void close();
  }
}
