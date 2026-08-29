package com.jizza.engine.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ModelTier;
import java.util.List;
import java.util.UUID;

/**
 * @param autonomous true when nobody is watching (scheduler / workflow), which makes the kill switch
 *                   and autonomy policy binding before any tool executes.
 */
public record AgentRequest(UUID userId, UUID agentId, String instruction, JsonNode input,
                           List<com.jizza.engine.api.llm.Msg> history, ModelTier tier,
                           boolean autonomous, String traceId) {

  public static AgentRequest of(UUID userId, UUID agentId, String instruction) {
    return new AgentRequest(userId, agentId, instruction, null, List.of(), ModelTier.STANDARD, false, null);
  }

  public AgentRequest autonomous(boolean v) {
    return new AgentRequest(userId, agentId, instruction, input, history, tier, v, traceId);
  }

  public AgentRequest withHistory(List<com.jizza.engine.api.llm.Msg> h) {
    return new AgentRequest(userId, agentId, instruction, input, h, tier, autonomous, traceId);
  }

  public AgentRequest withTier(ModelTier t) {
    return new AgentRequest(userId, agentId, instruction, input, history, t, autonomous, traceId);
  }
}
