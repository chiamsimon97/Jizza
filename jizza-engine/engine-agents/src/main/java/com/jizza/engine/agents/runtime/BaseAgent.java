package com.jizza.engine.agents.runtime;

import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityTypes;
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
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.RunBudget;
import com.jizza.engine.api.memory.MemoryContextProvider;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
 * handed a delegated task, starts the turn knowing what the user already told Jizza. It goes in a
 * system block of its own after the agent's instructions, so the instructions stay a stable prefix
 * the provider can cache while what is remembered changes from request to request.
 *
 * <p>Each run, each model call and (in the Capability Registry) each tool call is an observation,
 * so a trace shows where a slow or costly turn spent its time. Only names, tiers, counts and
 * outcomes are recorded: never a prompt, an argument or a reply.
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
  private ObservationRegistry observations = ObservationRegistry.NOOP;

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

  /** Tracing, when the application has it; runs untraced otherwise. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setObservations(ObjectProvider<ObservationRegistry> registry) {
    ObservationRegistry available = registry.getIfAvailable();
    if (available != null) this.observations = available;
  }

  @Override
  public AgentResult run(AgentRequest req) {
    // The run every memory read in it is stamped with: the one the caller named, or the run this
    // one was started from (a delegation), or a fresh one.
    AccessContext context = req.traceId() != null ? AccessContext.fromTrace(req.traceId())
        : AccessContext.current().orElseGet(() -> AccessContext.fromTrace(null));
    return context.run(() -> com.jizza.engine.api.agent.TrialRun.run(req.dryRun(),
        () -> runInContext(req)));
  }

  private AgentResult runInContext(AgentRequest req) {
    AgentEntity agent = agents.findById(req.agentId())
        .orElseThrow(() -> new NotFoundException("Agent", req.agentId()));
    // An agent only ever works for the account that owns it, whatever named it: a task, a
    // workflow step or a delegation. Someone else's agent is treated as not existing.
    if (req.userId() != null && !req.userId().equals(agent.getUserId())) {
      throw new NotFoundException("Agent", req.agentId());
    }

    // A trial may run a draft or paused agent, since nothing it does is carried out.
    boolean trialOfUnfinished = req.dryRun() && !"archived".equals(agent.getStatus());
    if (!trialOfUnfinished
        && (!agent.isEnabled() || !autonomyGate.agentEnabled(agent.getId()))) {
      throw new AutonomyDeniedException(agent.getName() + " is currently disabled");
    }
    if (req.autonomous()) {
      killSwitch.requireActive(req.userId());
    }
    if (req.autonomous() && overMonthlyLimit(agent)) {
      throw new BudgetExceededException(agent.getName() + " has reached its monthly spending "
          + "limit, so it doesn't work unattended until next month. Raise the limit on its page "
          + "to let it carry on.");
    }
    if (!costs.withinBudget(req.userId())) {
      throw new BudgetExceededException(
          "The model spend ceiling for this window has been reached; raise "
              + "jizza.agents.budget.per-window-units or wait for the window to roll over.");
    }

    Observation run = Observation.createNotStarted("jizza.agent.run", observations)
        .contextualName("agent " + agent.getKey())
        .lowCardinalityKeyValue("agent.key", agent.getKey())
        .lowCardinalityKeyValue("autonomous", String.valueOf(req.autonomous()))
        .lowCardinalityKeyValue("dry_run", String.valueOf(req.dryRun()))
        .start();
    try (Observation.Scope scope = run.openScope()) {
      AgentResult result = loop(req, agent, run);
      run.lowCardinalityKeyValue("outcome", result.truncatedByBudget() ? "budget" : "done");
      return result;
    } catch (RuntimeException e) {
      run.error(e);
      throw e;
    } finally {
      run.stop();
    }
  }

  private AgentResult loop(AgentRequest req, AgentEntity agent, Observation run) {
    MemoryContextProvider.MemoryContext remembered = recall(req);
    // A specialist leading the thread this turn is in talks to the user itself.
    boolean leadsThread = !agent.isOrchestrator()
        && com.jizza.engine.api.chat.ChatScope.leadsThread(agent.getId());
    String systemPrompt = AgentPrompts.systemPrompt(agent.getSystemPrompt(), agent.getName(),
        agent.isOrchestrator(), leadsThread);

    List<Msg> messages = new ArrayList<>();
    messages.add(Msg.system(systemPrompt));
    if (!remembered.isEmpty()) messages.add(Msg.system(remembered.rendered()));
    messages.addAll(req.history());
    messages.add(Msg.user(userTurn(req)));

    List<ToolSpec> tools = capabilities.toolsFor(agent.getId());
    RunBudget budget = new RunBudget(props.getLoop().getMaxToolIterations(),
        props.getLoop().getMaxTokensPerRun());
    List<ToolCall> callsMade = new ArrayList<>();
    // An agent set to think harder always does; otherwise the caller's tier stands.
    ModelTier tier = "deep".equalsIgnoreCase(agent.getModelTier()) && req.tier() != ModelTier.DEEP
        ? ModelTier.DEEP : req.tier();
    String model = llm.modelFor(tier);
    run.lowCardinalityKeyValue("tier", tier.name()).lowCardinalityKeyValue("model", model);
    int inputTokens = 0;
    int outputTokens = 0;
    String lastText = "";

    if (!remembered.memoryIds().isEmpty()) {
      Activity.emit(ActivityTypes.MEMORY_USED, agent.getId(), agent.getName(), p -> {
        p.put("count", remembered.memoryIds().size());
        var ids = p.putArray("memoryIds");
        remembered.memoryIds().forEach(id -> ids.add(id.toString()));
        var items = p.putArray("items");
        for (MemoryContextProvider.Item item : remembered.items()) {
          items.addObject().put("id", item.id().toString()).put("text", item.text())
              .put("category", item.category()).put("reason", item.reason())
              .put("score", Math.round(item.score() * 100) / 100.0).put("confirmed", item.confirmed());
        }
      });
    }
    // Only the words of whoever is talking to the user stream: Jizza's, or a thread lead's. A
    // specialist's answer to a delegated task goes back to the agent that asked (R20).
    boolean streams = (agent.isOrchestrator() || leadsThread) && Activity.active();

    audit.record(req.userId(), ActorType.AGENT, agent.getId(), "agent.run.start", agent.getKey(),
        Json.obj().put("autonomous", req.autonomous()).put("tier", tier.name())
            .put("dryRun", req.dryRun())
            .put("memoryFacts", remembered.memoryIds().size()));

    try {
      while (true) {
        Activity.checkCancelled();
        budget.nextIteration();
        int segment = budget.iterations();
        LlmResponse response = call(model, messages, tools, segment, streams, agent);
        inputTokens += response.inputTokens();
        outputTokens += response.outputTokens();
        costs.record(req.userId(), model, response, "agent:" + agent.getKey());
        budget.addTokens(response.budgetTokens() - response.outputTokens(), response.outputTokens());
        // A stop pressed while the model was thinking wins over whatever it came back with.
        Activity.checkCancelled();

        if (response.text() != null && !response.text().isBlank()) lastText = response.text();
        if (response.paused() && !response.wantsTools()) {
          // The provider paused its own work part-way; sending its reply back lets it carry on.
          messages.add(Msg.assistantTurn(response));
          continue;
        }
        if (!response.wantsTools()) {
          audit.record(req.userId(), ActorType.AGENT, agent.getId(), "agent.run.done",
              agent.getKey(), Json.obj().put("iterations", budget.iterations()));
          return new AgentResult(agent.getId(), agent.getName(), lastText, callsMade, false,
              inputTokens, outputTokens, null);
        }

        if (streams && response.text() != null && !response.text().isBlank()) {
          // What was streamed so far came before tool calls: narration, not the answer.
          Activity.emit(ActivityTypes.SEGMENT_PREAMBLE, agent.getId(), agent.getName(),
              p -> p.put("segment", segment).put("text", response.text()));
        }
        // The model's turn exactly as it came back, thinking included, so the history the next
        // round sends only ever grows at the end.
        messages.add(Msg.assistantTurn(response));
        List<Msg.ToolResultRef> results = new ArrayList<>();
        for (ToolCall call : response.toolCalls()) {
          Activity.checkCancelled();
          callsMade.add(call);
          ToolResult result = req.dryRun()
              ? capabilities.invoke(agent.getId(), call, req.autonomous(), true)
              : capabilities.invoke(agent.getId(), call, req.autonomous());
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

  /** One model call, observed: which model, which round, and what it cost in tokens. */
  private LlmResponse call(String model, List<Msg> messages, List<ToolSpec> tools, int segment,
                           boolean streams, AgentEntity agent) {
    Observation observation = Observation.createNotStarted("jizza.llm.call", observations)
        .contextualName("llm " + model)
        .lowCardinalityKeyValue("model", model)
        .lowCardinalityKeyValue("agent.key", agent.getKey())
        .lowCardinalityKeyValue("stream", String.valueOf(streams))
        .highCardinalityKeyValue("round", String.valueOf(segment))
        .start();
    try (Observation.Scope scope = observation.openScope()) {
      LlmResponse response = streams
          ? llm.stream(model, messages, tools, piece -> {
              Activity.checkCancelled();
              Activity.emit(ActivityTypes.DELTA, agent.getId(), agent.getName(),
                  p -> p.put("text", piece).put("segment", segment));
            })
          : llm.complete(model, messages, tools);
      observation.lowCardinalityKeyValue("wants_tools", String.valueOf(response.wantsTools()))
          .highCardinalityKeyValue("tokens.input", String.valueOf(response.inputTokens()))
          .highCardinalityKeyValue("tokens.output", String.valueOf(response.outputTokens()))
          .highCardinalityKeyValue("tokens.cache_read", String.valueOf(response.cacheReadTokens()))
          .highCardinalityKeyValue("tokens.cache_write", String.valueOf(response.cacheWriteTokens()));
      return response;
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
  }

  /** True when a custom agent has a monthly limit and this month's spend has reached it. */
  private boolean overMonthlyLimit(AgentEntity agent) {
    if (agent.getMonthlyCostLimit() == null) return false;
    java.time.Instant monthStart = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
        .withDayOfMonth(1).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    double spent = costs.spendSince(agent.getUserId(), "agent:" + agent.getKey(), monthStart);
    return spent >= agent.getMonthlyCostLimit().doubleValue();
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
