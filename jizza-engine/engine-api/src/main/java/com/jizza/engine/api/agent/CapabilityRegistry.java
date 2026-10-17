package com.jizza.engine.api.agent;

import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import java.util.List;
import java.util.UUID;

/**
 * The per-agent tool allow-list (R28). {@link #invoke} is the single choke point where the kill
 * switch, the autonomy policy and the audit log are consulted before anything executes.
 */
public interface CapabilityRegistry {

  List<ToolSpec> toolsFor(UUID agentId);

  ToolResult invoke(UUID agentId, ToolCall call);

  /** Same as {@link #invoke} but declares whether the caller is running unattended. */
  ToolResult invoke(UUID agentId, ToolCall call, boolean autonomous);

  /**
   * A trial run: reading tools and the agent's own notes run as usual, but nothing that changes
   * the outside world is executed. Such a call is answered with what the guardrails would do.
   */
  default ToolResult invoke(UUID agentId, ToolCall call, boolean autonomous, boolean dryRun) {
    if (dryRun) throw new UnsupportedOperationException("Trial runs are not supported here");
    return invoke(agentId, call, autonomous);
  }

  /**
   * Runs the exact call held on an approved permission request: the tool and arguments stored
   * when it was asked for, by the agent that asked, never whatever a model asks for now. Every
   * other check (agent enabled, still granted, schema, a deny rule) still applies; only the
   * {@code ask} that the user just answered is satisfied.
   */
  default ApprovedCall runApproved(UUID userId, UUID notificationId) {
    throw new UnsupportedOperationException("Approved calls are not supported here");
  }

  void grant(UUID agentId, CapabilityType type, UUID capabilityRef);

  void revoke(UUID agentId, CapabilityType type, UUID capabilityRef);

  boolean hasCapability(UUID agentId, String toolName);

  /** A capability granted to an agent, by what it is rather than the tool it becomes. */
  record Grant(CapabilityType type, UUID ref) {}

  /** What the agent has been granted, whether or not each is usable right now. */
  default List<Grant> grants(UUID agentId) {
    return List.of();
  }
}
