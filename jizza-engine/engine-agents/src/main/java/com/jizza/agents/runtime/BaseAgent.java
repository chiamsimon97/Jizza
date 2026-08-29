package com.jizza.agents.runtime;

import com.jizza.agents.config.AgentProperties;
import com.jizza.common.agent.Agent;
import com.jizza.common.agent.AgentRequest;
import com.jizza.common.agent.AgentResult;
import com.jizza.common.agent.CapabilityRegistry;
import com.jizza.common.audit.AuditLog;
import com.jizza.common.autonomy.AutonomyGate;
import com.jizza.common.autonomy.KillSwitch;
import com.jizza.common.enums.ActorType;
import com.jizza.common.error.AutonomyDeniedException;
import com.jizza.common.error.BudgetExceededException;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.llm.CostAccountant;
import com.jizza.common.llm.LlmClient;
import com.jizza.common.llm.LlmResponse;
import com.jizza.common.llm.Msg;
import com.jizza.common.llm.RunBudget;
import com.jizza.common.tool.ToolCall;
import com.jizza.common.tool.ToolResult;
import com.jizza.common.tool.ToolSpec;
import com.jizza.common.util.Json;
import com.jizza.persistence.entity.AgentEntity;
import com.jizza.persistence.repository.AgentRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The reusable Base Agent (R15): a system prompt, the tools this agent is allowed to hold, and a
 * bounded tool-use loop.
 *
 * <p>Three things bound the loop (R16): an iteration cap, a per-run token budget, and the user's
 * spend ceiling. Hitting any of them ends the turn with what the agent has so far rather than
 * spinning.
 */
@Service
public class BaseAgent implements Agent {

  private static final Logger log = LoggerFactory.getLogger(BaseAgent.class);

  private final AgentRepository agents;
  private final LlmClient llm;
  private final CapabilityRegistry capabilities;
  private final CostAccountant costs;
  private final AutonomyGate autonomyGate;
  private final KillSwitch killSwitch;
  private final AuditLog audit;
  private final AgentProperties props;

  public BaseAgent(AgentRepository agents, LlmClient llm, CapabilityRegistry capabilities,
                   CostAccountant costs, AutonomyGate autonomyGate, KillSwitch killSwitch,
                   AuditLog audit, AgentProperties props) {
    this.agents = agents;
    this.llm = llm;
    this.capabilities = capabilities;
    this.costs = costs;
    this.autonomyGate = autonomyGate;
    this.killSwitch = killSwitch;
    this.audit = audit;
    this.props = props;
  }

  @Override
  public AgentResult run(AgentRequest req) {
    AgentEntity agent = agents.findById(req.agentId())
        .orElseThrow(() -> new NotFoundException("Agent", req.agentId()));

    if (!agent.isEnabled() || !autonomyGate.agentEnabled(agent.getId())) {
      throw new AutonomyDeniedException(agent.getName() + " is currently disabled");
    }
    if (req.autonomous()) {
      killSwitch.requireActive(req.userId());
    }
    if (!costs.withinBudget(req.userId())) {
      throw new BudgetExceededException(
          "The model spend ceiling for this window has been reached; raise "
              + "jizza.agents.budget.per-window-units or wait for the window to roll over.");
    }

    List<Msg> messages = new ArrayList<>();
    messages.add(Msg.system(AgentPrompts.systemPrompt(agent.getSystemPrompt(), agent.getName(),
        agent.isOrchestrator())));
    messages.addAll(req.history());
    messages.add(Msg.user(userTurn(req)));

    List<ToolSpec> tools = capabilities.toolsFor(agent.getId());
    RunBudget budget = new RunBudget(props.getLoop().getMaxToolIterations(),
        props.getLoop().getMaxTokensPerRun());
    List<ToolCall> callsMade = new ArrayList<>();
    String model = llm.modelFor(req.tier());
    int inputTokens = 0;
    int outputTokens = 0;
    String lastText = "";

    audit.record(req.userId(), ActorType.AGENT, agent.getId(), "agent.run.start", agent.getKey(),
        Json.obj().put("autonomous", req.autonomous()).put("tier", req.tier().name()));

    try {
      while (true) {
        budget.nextIteration();
        LlmResponse response = llm.complete(model, messages, tools);
        inputTokens += response.inputTokens();
        outputTokens += response.outputTokens();
        costs.record(req.userId(), response.model() == null ? model : response.model(),
            response.inputTokens(), response.outputTokens(), "agent:" + agent.getKey());
        budget.addTokens(response.inputTokens(), response.outputTokens());

        if (response.text() != null && !response.text().isBlank()) lastText = response.text();
        if (!response.wantsTools()) {
          audit.record(req.userId(), ActorType.AGENT, agent.getId(), "agent.run.done",
              agent.getKey(), Json.obj().put("iterations", budget.iterations()));
          return new AgentResult(agent.getId(), agent.getName(), lastText, callsMade, false,
              inputTokens, outputTokens, null);
        }

        messages.add(Msg.assistantToolCalls(response.text(), response.toolCalls()));
        List<Msg.ToolResultRef> results = new ArrayList<>();
        for (ToolCall call : response.toolCalls()) {
          callsMade.add(call);
          ToolResult result = capabilities.invoke(agent.getId(), call, req.autonomous());
          results.add(new Msg.ToolResultRef(call.id(), truncate(result)));
        }
        messages.add(Msg.toolResults(results));
      }
    } catch (BudgetExceededException e) {
      // A hit budget is a normal outcome, not a crash: return what the agent produced and say so.
      log.info("Agent {} stopped on its budget: {}", agent.getKey(), e.getMessage());
      audit.record(req.userId(), ActorType.AGENT, agent.getId(), "agent.run.budget_exhausted",
          agent.getKey(), Json.obj().put("reason", e.getMessage()));
      String text = lastText.isBlank()
          ? "I stopped before finishing: " + e.getMessage()
          : lastText;
      return new AgentResult(agent.getId(), agent.getName(), text, callsMade, true,
          inputTokens, outputTokens, null);
    }
  }

  private String userTurn(AgentRequest req) {
    if (req.input() == null || req.input().isNull()) return req.instruction();
    return req.instruction() + "\n\nStructured input:\n" + Json.write(req.input());
  }

  /** Keeps one talkative connector response from consuming the whole context window. */
  private ToolResult truncate(ToolResult result) {
    if (!result.ok() || result.output() == null) return result;
    String serialized = Json.write(result.output());
    int limit = props.getLoop().getMaxToolResultChars();
    if (serialized.length() <= limit) return result;
    return ToolResult.ok(serialized.substring(0, limit)
        + "\n...[truncated, " + serialized.length() + " characters total]");
  }
}
