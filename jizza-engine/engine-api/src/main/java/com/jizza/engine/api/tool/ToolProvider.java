package com.jizza.engine.api.tool;

import java.util.List;
import java.util.UUID;

/**
 * How a module contributes tools without any other module depending on it. Knowledge, connector
 * tools, skills, delegation, scheduling, notifications and procedure proposal each publish a
 * provider; the Capability Registry aggregates them and is the only thing that executes one, so the
 * kill switch, the autonomy policy and the audit log are never bypassed.
 */
public interface ToolProvider {

  /** Tools this provider offers to the given agent, already filtered by its allow-list. */
  List<ToolSpec> toolsFor(UUID agentId);

  boolean supports(UUID agentId, String toolName);

  ToolResult invoke(UUID agentId, ToolCall call);

  /**
   * True when a tool's arguments carry the user's personal detail (a fact to remember, a note to
   * keep), so the audit log records that the call happened but not what it said (R13). The audit
   * log is append-only, so anything written there could never be forgotten.
   */
  default boolean redactArguments(String toolName) { return false; }
}
