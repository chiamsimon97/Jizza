package com.jizza.common.agent;

import com.jizza.common.enums.CapabilityType;
import com.jizza.common.tool.ToolCall;
import com.jizza.common.tool.ToolResult;
import com.jizza.common.tool.ToolSpec;
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

  void grant(UUID agentId, CapabilityType type, UUID capabilityRef);

  void revoke(UUID agentId, CapabilityType type, UUID capabilityRef);

  boolean hasCapability(UUID agentId, String toolName);
}
