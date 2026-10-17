package com.jizza.spikes.adk;

import com.google.adk.agents.Instruction;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.agents.runtime.AgentPrompts;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.agent.CapabilityRegistry;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.AutonomyDeniedException;
import com.jizza.engine.api.error.BudgetExceededException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.llm.RunBudget;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link Agent} on ADK: the same contract as Jizza's own {@code BaseAgent}, so it could stand in
 * for it behind the interface. ADK's {@link LlmAgent} and {@link InMemoryRunner} run the loop;
 * {@link JizzaModel} is the model, {@link CapabilityTool} each tool, {@link JizzaRunPlugin} the
 * budgets and accounting. The checks before a run starts are Jizza's, unchanged.
 */
public final class AdkAgent implements Agent {

  private static final String APP = "jizza";

  private final AgentRepository agents;
  private final LlmClient llm;
  private final CapabilityRegistry capabilities;
  private final CostAccountant costs;
  private final AutonomyGate autonomyGate;
  private final KillSwitch killSwitch;
  private final AuditLog audit;
  private final AgentProperties props;

  public AdkAgent(AgentRepository agents, LlmClient llm, CapabilityRegistry capabilities,
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
    if (req.autonomous()) killSwitch.requireActive(req.userId());
    if (!costs.withinBudget(req.userId())) {
      throw new BudgetExceededException("The model spend ceiling for this window has been reached");
    }

    ModelTier tier = req.tier() == null ? ModelTier.STANDARD : req.tier();
    String model = llm.modelFor(tier);
    String instructions = AgentPrompts.systemPrompt(agent.getSystemPrompt(), agent.getName(),
        agent.isOrchestrator());
    List<CapabilityTool> tools = capabilities.toolsFor(agent.getId()).stream()
        .map(spec -> new CapabilityTool(spec, capabilities, agent.getId(), req.autonomous()))
        .toList();

    LlmAgent adkAgent = LlmAgent.builder()
        .name(agent.getKey())
        .model(new JizzaModel(model, llm))
        // A provider, not a string: ADK fills {placeholders} in a plain string from session state,
        // and an agent's instructions are the user's own words, which may contain braces.
        .instruction(new Instruction.Provider(ctx -> io.reactivex.rxjava3.core.Single.just(instructions)))
        .tools(tools)
        .disallowTransferToParent(true)
        .disallowTransferToPeers(true)
        .build();
    JizzaRunPlugin run = new JizzaRunPlugin(req.userId(), model, "agent:" + agent.getKey(), costs,
        new RunBudget(props.getLoop().getMaxToolIterations(), props.getLoop().getMaxTokensPerRun()));
    InMemoryRunner runner = new InMemoryRunner(adkAgent, APP, List.of(run));

    String userId = req.userId().toString();
    Session session = runner.sessionService().createSession(APP, userId).blockingGet();
    for (Msg turn : req.history()) {
      boolean fromUser = turn.role() == Msg.Role.USER;
      runner.sessionService().appendEvent(session, Event.builder()
          .id(UUID.randomUUID().toString())
          .invocationId("history")
          .author(fromUser ? "user" : agent.getKey())
          .content(Content.builder().role(fromUser ? "user" : "model")
              .parts(Part.fromText(turn.text() == null ? "" : turn.text())).build())
          .build()).blockingGet();
    }

    audit.record(req.userId(), ActorType.AGENT, agent.getId(), "agent.run.start", agent.getKey(),
        Json.obj().put("autonomous", req.autonomous()).put("runtime", "adk"));
    boolean streams = agent.isOrchestrator() && Activity.active();
    RunConfig config = RunConfig.builder()
        .streamingMode(streams ? RunConfig.StreamingMode.SSE : RunConfig.StreamingMode.NONE)
        .maxLlmCalls(props.getLoop().getMaxToolIterations() + 2)
        .build();

    List<ToolCall> callsMade = new ArrayList<>();
    StringBuilder lastText = new StringBuilder();
    runner.runAsync(userId, session.id(), Content.fromParts(Part.fromText(req.instruction())), config)
        .blockingForEach(event -> {
          if (event.partial().orElse(false)) {
            String piece = event.stringifyContent();
            if (streams && !piece.isEmpty()) {
              Activity.emit(ActivityTypes.DELTA, agent.getId(), agent.getName(),
                  p -> p.put("text", piece));
            }
            return;
          }
          event.functionCalls().forEach(fc -> callsMade.add(new ToolCall(fc.id().orElse(""),
              fc.name().orElse(""), Json.toNode(fc.args().orElse(java.util.Map.of())))));
          String text = event.content().flatMap(Content::parts).orElse(List.of()).stream()
              .filter(p -> !p.thought().orElse(false))
              .map(p -> p.text().orElse("")).reduce("", String::concat);
          if (!text.isBlank() && agent.getKey().equals(event.author())) {
            lastText.setLength(0);
            lastText.append(text);
          }
        });

    boolean truncated = run.stoppedBecause != null;
    audit.record(req.userId(), ActorType.AGENT, agent.getId(),
        truncated ? "agent.run.budget_exhausted" : "agent.run.done", agent.getKey(), null);
    return new AgentResult(agent.getId(), agent.getName(), lastText.toString(), callsMade,
        truncated, run.inputTokens, run.outputTokens, null);
  }
}
