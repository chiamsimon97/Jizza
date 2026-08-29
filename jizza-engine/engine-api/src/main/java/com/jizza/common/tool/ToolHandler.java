package com.jizza.common.tool;

import java.util.UUID;

/**
 * A locally implemented tool (knowledge, notify, delegate, schedule, propose_workflow, skills…).
 * Generated connector tools are executed by {@code ToolExecutor} instead.
 */
public interface ToolHandler {

  /** Stable tool name as exposed to the model. */
  String name();

  /** Schema the model sees. */
  ToolSpec spec();

  /** True if this handler should be offered to the given agent. */
  boolean availableTo(UUID agentId);

  ToolResult invoke(UUID agentId, ToolCall call);

  /** Side-effecting handlers are gated harder by the autonomy policy. */
  default boolean sideEffecting() { return spec().sideEffecting(); }
}
