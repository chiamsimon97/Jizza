package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.AutonomyDeniedException;
import com.jizza.engine.api.error.BudgetExceededException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Runs a named agent as one step of a workflow. The step is autonomous by definition -- no one is
 * watching a scheduled run -- so the kill switch and the per-agent toggle apply inside the agent
 * runtime as usual.
 */
@Component
public class AgentStepNodeExecutor implements NodeExecutor {

  private final Agent baseAgent;
  private final JexlSandbox sandbox;

  public AgentStepNodeExecutor(Agent baseAgent, JexlSandbox sandbox) {
    this.baseAgent = baseAgent;
    this.sandbox = sandbox;
  }

  @Override
  public NodeType type() { return NodeType.AGENT_STEP; }

  @Override
  public Outcome execute(Execution execution) {
    JsonNode config = execution.node().config();
    if (config == null || !config.hasNonNull("agentId")) {
      return Outcome.permanentFailure("This node does not say which agent should run");
    }
    UUID agentId;
    try {
      agentId = UUID.fromString(config.path("agentId").asText());
    } catch (IllegalArgumentException e) {
      return Outcome.permanentFailure("'agentId' is not an agent id");
    }

    String instruction;
    try {
      instruction = config.hasNonNull("instructionExpr")
          ? sandbox.evaluate(config.path("instructionExpr").asText(),
              execution.context().variables()).asText()
          : config.path("instruction").asText("");
    } catch (RuntimeException e) {
      return Outcome.permanentFailure("The step's instruction could not be prepared: "
          + e.getMessage());
    }
    if (instruction.isBlank()) {
      return Outcome.permanentFailure("This node has no instruction for the agent");
    }

    ModelTier tier = config.hasNonNull("tier")
        ? ModelTier.valueOf(config.path("tier").asText().toUpperCase(java.util.Locale.ROOT))
        : ModelTier.STANDARD;

    try {
      AgentResult result = baseAgent.run(new AgentRequest(execution.userId(), agentId, instruction,
          execution.context().input(), List.of(), tier, true, execution.runId().toString()));
      if (!result.ok()) return Outcome.retryable(result.error());
      return Outcome.completed(Json.obj()
          .put("agent", result.agentName())
          .put("text", result.text())
          .put("truncated", result.truncatedByBudget()));
    } catch (AutonomyDeniedException | BudgetExceededException e) {
      // Neither is a transport hiccup: retrying would hit the same wall.
      return Outcome.permanentFailure(e.getMessage());
    } catch (RuntimeException e) {
      return Outcome.retryable(String.valueOf(e.getMessage()));
    }
  }
}
