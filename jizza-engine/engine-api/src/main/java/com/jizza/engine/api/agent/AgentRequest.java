package com.jizza.engine.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ModelTier;
import java.util.List;
import java.util.UUID;

/**
 * @param autonomous true when nobody is watching (scheduler / workflow), which makes the kill switch
 *                   and autonomy policy binding before any tool executes.
 * @param dryRun     a trial of the agent: nothing that changes the outside world is executed
 * @param model      a model the person picked for this request (a model reference, see
 *                   {@link com.jizza.engine.api.llm.ModelRefs}); null leaves the agent's own
 *                   default model or its tier
 * @param effort     how hard the model thinks this time (low, medium, high), overriding the tier's
 *                   setting; null keeps it
 */
public record AgentRequest(UUID userId, UUID agentId, String instruction, JsonNode input,
                           List<com.jizza.engine.api.llm.Msg> history, ModelTier tier,
                           boolean autonomous, String traceId, boolean dryRun, String model,
                           String effort) {

  public AgentRequest(UUID userId, UUID agentId, String instruction, JsonNode input,
                      List<com.jizza.engine.api.llm.Msg> history, ModelTier tier,
                      boolean autonomous, String traceId, boolean dryRun) {
    this(userId, agentId, instruction, input, history, tier, autonomous, traceId, dryRun, null, null);
  }

  public AgentRequest(UUID userId, UUID agentId, String instruction, JsonNode input,
                      List<com.jizza.engine.api.llm.Msg> history, ModelTier tier,
                      boolean autonomous, String traceId) {
    this(userId, agentId, instruction, input, history, tier, autonomous, traceId, false);
  }

  public static AgentRequest of(UUID userId, UUID agentId, String instruction) {
    return new AgentRequest(userId, agentId, instruction, null, List.of(), ModelTier.STANDARD, false, null);
  }

  public AgentRequest autonomous(boolean v) {
    return new AgentRequest(userId, agentId, instruction, input, history, tier, v, traceId, dryRun, model, effort);
  }

  public AgentRequest withHistory(List<com.jizza.engine.api.llm.Msg> h) {
    return new AgentRequest(userId, agentId, instruction, input, h, tier, autonomous, traceId, dryRun, model, effort);
  }

  public AgentRequest withTier(ModelTier t) {
    return new AgentRequest(userId, agentId, instruction, input, history, t, autonomous, traceId, dryRun, model, effort);
  }

  public AgentRequest asDryRun() {
    return new AgentRequest(userId, agentId, instruction, input, history, tier, autonomous, traceId, true, model, effort);
  }

  /** The same request on the model the person picked, or the usual one when {@code m} is null. */
  public AgentRequest withModel(String m) {
    return new AgentRequest(userId, agentId, instruction, input, history, tier, autonomous, traceId, dryRun, m, effort);
  }

  public AgentRequest withEffort(String e) {
    return new AgentRequest(userId, agentId, instruction, input, history, tier, autonomous, traceId, dryRun, model, e);
  }
}
