package com.jizza.engine.api.agent;

import com.jizza.engine.api.tool.ToolResult;
import java.util.UUID;

/** A call the user approved, run exactly as it was asked for, and what it returned. */
public record ApprovedCall(UUID notificationId, UUID agentId, String agentName, String tool,
                           ToolResult result) {}
