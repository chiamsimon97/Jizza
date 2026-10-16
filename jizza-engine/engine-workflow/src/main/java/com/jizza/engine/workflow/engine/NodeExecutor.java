package com.jizza.engine.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.UUID;

/** How one kind of node runs. One implementation per {@code NodeType}. */
public interface NodeExecutor {

  com.jizza.engine.api.enums.NodeType type();

  Outcome execute(Execution execution);

  /**
   * @param runId    the run this node belongs to
   * @param userId   owner of the run, for autonomy and audit
   * @param node     the node being executed
   * @param context  the saga state, already carrying every earlier node's output
   * @param attempt  1 for the first try; retries increment it
   * @param workflowId      the workflow being run, so its own guardrails apply to its steps
   * @param workflowVersion the version of it being run
   */
  record Execution(UUID runId, UUID userId, UUID owningAgentId, WorkflowNode node,
                   RunContext context, int attempt, UUID workflowId, int workflowVersion) {

    public Execution(UUID runId, UUID userId, UUID owningAgentId, WorkflowNode node,
                     RunContext context, int attempt) {
      this(runId, userId, owningAgentId, node, context, attempt, null, 0);
    }
  }

  /**
   * What a node execution produced.
   *
   * @param status   how the engine should proceed
   * @param output   the node's result, checkpointed and visible to later nodes
   * @param branch   for a branch node, which outlet was taken
   * @param message  why, when the status is not {@code COMPLETED}
   */
  record Outcome(Status status, JsonNode output, String branch, String message) {

    public enum Status {
      /** The node finished; checkpoint it and move on. */
      COMPLETED,
      /** The node failed but may succeed later; the engine applies the retry policy. */
      RETRYABLE,
      /** The node failed in a way retrying will not fix. */
      PERMANENT_FAILURE,
      /** The run must stop here until the user supplies an instruction. */
      AWAIT_INSTRUCTION,
      /** The run must stop here until the user approves the action. */
      AWAIT_PERMISSION
    }

    public static Outcome completed(JsonNode output) {
      return new Outcome(Status.COMPLETED, output, null, null);
    }

    public static Outcome branched(String outlet, JsonNode output) {
      return new Outcome(Status.COMPLETED, output, outlet, null);
    }

    public static Outcome retryable(String message) {
      return new Outcome(Status.RETRYABLE, null, null, message);
    }

    public static Outcome permanentFailure(String message) {
      return new Outcome(Status.PERMANENT_FAILURE, null, null, message);
    }

    public static Outcome awaitInstruction(String prompt) {
      return new Outcome(Status.AWAIT_INSTRUCTION, null, null, prompt);
    }

    public static Outcome awaitPermission(String prompt) {
      return new Outcome(Status.AWAIT_PERMISSION, null, null, prompt);
    }
  }
}
