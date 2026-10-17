package com.jizza.engine.api.agent;

import com.jizza.engine.api.tool.ToolCall;
import java.util.List;
import java.util.UUID;

/**
 * Structured outcome of one agent turn. {@code agentName} is what the user is shown; specialists are
 * always attributed by name when Jizza relays them (R19).
 */
public record AgentResult(UUID agentId, String agentName, String text, List<ToolCall> toolCallsMade,
                          boolean truncatedByBudget, int inputTokens, int outputTokens, String error) {

  public boolean ok() { return error == null; }

  public static AgentResult of(UUID agentId, String agentName, String text) {
    return new AgentResult(agentId, agentName, text, List.of(), false, 0, 0, null);
  }

  public static AgentResult failed(UUID agentId, String agentName, String error) {
    return new AgentResult(agentId, agentName, null, List.of(), false, 0, 0, error);
  }
}
