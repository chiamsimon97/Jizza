package com.jizza.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.agents.llm.DefaultCostAccountant;
import com.jizza.engine.agents.registry.DefaultCapabilityRegistry;
import com.jizza.engine.agents.runtime.BaseAgent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.ActionTypeSource;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.memory.MemoryContextProvider;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentSpec;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.autonomy.policy.PolicyAutonomyGate;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.LlmUsageEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.LlmUsageRepository;
import com.jizza.platform.usecase.agent.AgentTeamUseCase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * One scenario's world: the built-in agent as every account is given it, the real agent runtime,
 * the real Capability Registry and the real guardrails with no rules set (so the Charter's
 * conservative defaults decide), and the scenario's fixture tools in place of real services.
 * Nothing here can reach anything outside the process except the model.
 */
public final class EvalWorld {

  /** What one run of a scenario did. */
  record Outcome(AgentResult result, List<String> requested, List<ToolCall> calls,
                 List<String> executed,
                 Map<String, AutonomyRule> decisions, int rounds, int inputTokens,
                 int outputTokens, int cacheReadTokens, BigDecimal cost) {}

  /**
   * The agent runtime under test, given everything the product would give it. Jizza's own
   * {@link BaseAgent} unless another runtime (such as an experiment) is being compared with it.
   */
  @FunctionalInterface
  public interface Runtime {
    com.jizza.engine.api.agent.Agent create(AgentRepository agents, LlmClient llm,
                                            com.jizza.engine.api.agent.CapabilityRegistry registry,
                                            com.jizza.engine.api.llm.CostAccountant costs,
                                            com.jizza.engine.api.autonomy.AutonomyGate gate,
                                            KillSwitch killSwitch, AuditLog audit,
                                            AgentProperties props);

    Runtime BASE_AGENT = (agents, llm, registry, costs, gate, killSwitch, audit, props) ->
        new BaseAgent(agents, llm, registry, costs, gate, killSwitch, audit, props,
            new StaticListableBeanFactory().getBeanProvider(MemoryContextProvider.class));
  }

  private final EvalCase scenario;
  private final LlmClient llm;
  private final AgentProperties props;
  private final Runtime runtime;

  EvalWorld(EvalCase scenario, LlmClient llm, AgentProperties props, Runtime runtime) {
    this.scenario = scenario;
    this.llm = llm;
    this.props = props;
    this.runtime = runtime;
  }

  static AgentSpec builtIn(String key) {
    return AgentTeamUseCase.builtInTeam().stream().filter(s -> s.key().equals(key)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No built-in agent with key '" + key + "'"));
  }

  Outcome run() {
    UUID userId = UUID.randomUUID();
    AgentSpec spec = builtIn(scenario.agent());
    AgentEntity agent = new AgentEntity();
    agent.setId(UUID.randomUUID());
    agent.setUserId(userId);
    agent.setKey(spec.key());
    agent.setName(spec.name());
    agent.setDomain(spec.domain());
    agent.setSystemPrompt(spec.systemPrompt());
    agent.setOrchestrator(spec.orchestrator());
    agent.setEnabled(true);

    AgentRepository agents = Fakes.of(AgentRepository.class, Map.of(
        "findById", a -> Optional.of(agent).filter(x -> x.getId().equals(a[0]))));

    // ── Fixture tools, and what the user said each does ──────────────────────────
    Map<String, EvalCase.FixtureTool> fixtures = new LinkedHashMap<>();
    scenario.tools().forEach(t -> fixtures.put(t.name(), t));
    List<String> executed = new ArrayList<>();
    ToolProvider tools = new ToolProvider() {
      @Override public List<ToolSpec> toolsFor(UUID agentId) {
        return fixtures.values().stream().map(t -> new ToolSpec(t.name(), t.description(),
            t.schema() == null ? JsonSchemas.emptyObject() : t.schema(),
            t.actionType() == null || t.actionType().sideEffecting())).toList();
      }

      @Override public boolean supports(UUID agentId, String toolName) {
        return fixtures.containsKey(toolName);
      }

      @Override public ToolResult invoke(UUID agentId, ToolCall call) {
        executed.add(call.name());
        JsonNode result = fixtures.get(call.name()).result();
        return ToolResult.ok(result == null ? Json.obj().put("ok", true) : result);
      }
    };
    ActionTypeSource declared = (agentId, toolRef) ->
        Optional.ofNullable(fixtures.get(toolRef)).map(EvalCase.FixtureTool::actionType);

    // ── The real guardrails, with no rules: the conservative defaults decide ────
    PolicyAutonomyGate gate = new PolicyAutonomyGate(
        Fakes.of(AutonomyPolicyRepository.class, Map.of()), agents,
        Fakes.of(GeneratedToolRepository.class, Map.of()));
    StaticListableBeanFactory sources = new StaticListableBeanFactory();
    sources.addBean("fixtures", declared);
    gate.setActionTypeSources(sources.getBeanProvider(ActionTypeSource.class));

    Map<String, AutonomyRule> decisions = new LinkedHashMap<>();
    AuditLog audit = Fakes.of(AuditLog.class, Map.of("record", a -> {
      String action = (String) a[3];
      String tool = (String) a[4];
      JsonNode detail = (JsonNode) a[5];
      switch (action) {
        case "tool.call" -> decisions.put(tool, AutonomyRule.valueOf(detail.path("decision").asText()));
        case "tool.permission_requested" -> decisions.put(tool, AutonomyRule.ASK);
        case "tool.blocked" -> {
          if ("policy-deny".equals(detail.path("reason").asText())) decisions.put(tool, AutonomyRule.DENY);
        }
        default -> { }
      }
      return null;
    }));
    // Approvals are raised as they are in the product, so the model sees the same reply.
    NotificationBus notifications = Fakes.of(NotificationBus.class, Map.of("raise", a -> {
      NotificationContext ctx = (NotificationContext) a[2];
      return new NotificationDto(UUID.randomUUID(), ctx.userId(), (UUID) a[1], agent.getName(),
          (com.jizza.engine.api.enums.NotificationKind) a[0], ctx.body(), ctx.detail(),
          null, null, null, null, null, null, null, java.time.Instant.now(), null, null);
    }));
    KillSwitch killSwitch = new KillSwitch() {
      @Override public boolean paused(UUID u) { return false; }
      @Override public void setPaused(UUID u, boolean value) { }
      @Override public void requireActive(UUID u) { }
    };

    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("fixtures", tools);
    beans.addBean("notifications", notifications);
    DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
        beans.getBeanProvider(ToolProvider.class), agents,
        Fakes.of(AgentCapabilityRepository.class, Map.of()), gate, killSwitch, audit,
        beans.getBeanProvider(NotificationBus.class));

    // ── Spend, priced exactly as the product prices it ──────────────────────────
    List<LlmUsageEntity> usage = new ArrayList<>();
    LlmUsageRepository usageRepository = Fakes.of(LlmUsageRepository.class, Map.of(
        "save", a -> {
          usage.add((LlmUsageEntity) a[0]);
          return a[0];
        },
        "spendSince", a -> BigDecimal.ZERO,
        "spendSinceForPurpose", a -> BigDecimal.ZERO));
    DefaultCostAccountant costs = new DefaultCostAccountant(usageRepository, props);

    CountingLlm counting = new CountingLlm(llm);
    var agentRuntime = runtime.create(agents, counting, registry, costs, gate, killSwitch, audit,
        props);

    List<Msg> history = scenario.history().stream()
        .map(t -> "assistant".equalsIgnoreCase(t.role()) ? Msg.assistant(t.text()) : Msg.user(t.text()))
        .toList();
    AgentRequest request = new AgentRequest(userId, agent.getId(), scenario.instruction(), null,
        history, ModelTier.STANDARD, scenario.autonomous(), null);
    AgentResult result = agentRuntime.run(request);

    return new Outcome(result,
        result.toolCallsMade().stream().map(ToolCall::name).toList(),
        List.copyOf(result.toolCallsMade()), List.copyOf(executed),
        Map.copyOf(decisions), counting.calls,
        usage.stream().mapToInt(LlmUsageEntity::getInputTokens).sum(),
        usage.stream().mapToInt(LlmUsageEntity::getOutputTokens).sum(),
        usage.stream().mapToInt(LlmUsageEntity::getCacheReadTokens).sum(),
        usage.stream().map(LlmUsageEntity::getCostUnits).reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  /** Counts model rounds. */
  private static final class CountingLlm implements LlmClient {
    private final LlmClient delegate;
    int calls;

    CountingLlm(LlmClient delegate) { this.delegate = delegate; }

    @Override public LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools) {
      calls++;
      return delegate.complete(model, messages, tools);
    }

    @Override public LlmResponse stream(String model, List<Msg> messages, List<ToolSpec> tools,
                                        java.util.function.Consumer<String> onText) {
      calls++;
      return delegate.stream(model, messages, tools, onText);
    }

    @Override public String modelFor(ModelTier tier) { return delegate.modelFor(tier); }
  }
}
