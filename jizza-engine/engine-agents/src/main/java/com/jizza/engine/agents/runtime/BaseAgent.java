package com.jizza.engine.agents.runtime;

import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.cosmos.AccessContext;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.agent.CapabilityRegistry;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.AutonomyDeniedException;
import com.jizza.engine.api.error.BudgetExceededException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.llm.RunBudget;
import com.jizza.engine.api.memory.MemoryContextProvider;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The reusable Base Agent (R15): a system prompt, the tools this agent is allowed to hold, and a
 * bounded tool-use loop.
 *
 * <p>Three things bound the loop (R16): an iteration cap, a per-run token budget, and the user's
 * spend ceiling. Hitting any of them ends the turn with what the agent has so far rather than
 * spinning.
 *
 * <p>Before the first model call, the user's memory relevant to this request -- limited to what
 * this agent may see -- is placed in the system prompt, so every agent, including a specialist
 * handed a delegated task, starts the turn knowing what the user already told Jizza.
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
  private final ObjectProvider<MemoryContextProvider> memory;

  public BaseAgent(AgentRepository agents, LlmClient llm, CapabilityRegistry capabilities,
                   CostAccountant costs, AutonomyGate autonomyGate, KillSwitch killSwitch,
                   AuditLog audit, AgentProperties props,
                   ObjectProvider<MemoryContextProvider> memory) {
    this.agents = agents;
    this.llm = llm;
    this.capabilities = capabilities;
    this.costs = costs;
    this.autonomyGate = autonomyGate;
    this.killSwitch = killSwitch;
    this.audit = audit;
    this.props = props;
    this.memory = memory;
  }

  @Override
  public AgentResult run(AgentRequest req) {
    // The run every memory read in it is stamped with: the one the caller named, or the run this
    // one was started from (a delegation), or a fresh one.
    AccessContext context = req.traceId() != null ? AccessContext.fromTrace(req.traceId())
        : AccessContext.current().orElseGet(() -> AccessContext.fromTrace(null));
    return context.run(() -> runInContext(req));
  }

  private AgentResult runInContext(AgentRequest req) {
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

    MemoryContextProvider.MemoryContext remembered = recall(req);
    String systemPrompt = AgentPrompts.systemPrompt(agent.getSystemPrompt(), agent.getName(),
        agent.isOrchestrator());
    if (!remembered.isEmpty()) systemPrompt = systemPrompt + "\n\n" + remembered.rendered();

    List<Msg> messages = new ArrayList<>();
    messages.add(Msg.system(systemPrompt));
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
        Json.obj().put("autonomous", req.autonomous()).put("tier", req.tier().name())
            .put("memoryFacts", remembered.memoryIds().size()));

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

  /**
   * The memory this turn starts with. Never fatal: an agent that cannot reach its memory still
   * answers, it just knows less.
   */
  private MemoryContextProvider.MemoryContext recall(AgentRequest req) {
    MemoryContextProvider provider = memory.getIfAvailable();
    if (provider == null || req.instruction() == null || req.instruction().isBlank()) {
      return MemoryContextProvider.MemoryContext.EMPTY;
    }
    try {
      return provider.contextFor(req.userId(), req.agentId(), req.instruction());
    } catch (RuntimeException e) {
      log.warn("Memory unavailable for this turn: {}", e.getMessage());
      return MemoryContextProvider.MemoryContext.EMPTY;
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
