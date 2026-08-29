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

  void grant(UUID agentId, CapabilityType type, UUID capabilityRef);

  void revoke(UUID agentId, CapabilityType type, UUID capabilityRef);

  boolean hasCapability(UUID agentId, String toolName);
}
