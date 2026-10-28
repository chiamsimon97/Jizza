package com.jizza.engine.orchestrator.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivitySink;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.orchestrator.library.CallSteps;
import com.jizza.engine.orchestrator.library.ReuseArgs;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code app_propose_workflow}: Jizza puts a finished workflow plan in front of the user, who
 * approves it (and only then is it saved, as a draft) or asks for changes.
 *
 * <p>Like the other app tools it changes no data: the plan is recorded as a {@link UiAction} on the
 * reply. Each step is resolved to a real, enabled specialist here, and a step that changes
 * something outside Jizza is labelled with what the agent's autonomy rules would do with it today
 * (run on its own, ask the user, or never), so the user sees where the workflow will stop for them
 * before agreeing to it.
 */
@Component
public class WorkflowPlanToolProvider implements ToolProvider {

  public static final String PROPOSE = "app_propose_workflow";

  /**
   * Stands in for "an action of this agent that changes something" when asking the gate. It is no
   * real tool, so the gate treats it as consequential and the agent's side-effecting rule decides.
   */
  static final String SIDE_EFFECT_PROBE = "workflow_plan_side_effect";

  static final int MAX_STEPS = 12;

  private final AgentRepository agents;
  private final UiActionRecorder recorder;
  private final ObjectProvider<AutonomyGate> gate;

  private final CallSteps callSteps;

  public WorkflowPlanToolProvider(AgentRepository agents, UiActionRecorder recorder,
                                  ObjectProvider<AutonomyGate> gate) {
    this(agents, recorder, gate, null);
  }

  @Autowired
  public WorkflowPlanToolProvider(AgentRepository agents, UiActionRecorder recorder,
                                  ObjectProvider<AutonomyGate> gate, CallSteps callSteps) {
    this.agents = agents;
    this.recorder = recorder;
    this.gate = gate;
    this.callSteps = callSteps;
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return orchestrator(agentId).map(jizza -> List.of(spec(roster(jizza.getUserId())))).orElse(List.of());
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return PROPOSE.equals(toolName) && orchestrator(agentId).isPresent();
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<AgentEntity> jizza = orchestrator(agentId);
    if (jizza.isEmpty()) return ToolResult.error("Only Jizza may propose a workflow plan");
    if (!recorder.active()) {
      return ToolResult.error("The user is not looking at the app right now, so there is nowhere "
          + "to show the plan. Describe it in text instead.");
    }
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    String name = args.path("name").asText("").trim();
    if (name.isEmpty()) return ToolResult.error("'name' must not be empty");
    JsonNode steps = args.path("steps");
    if (!steps.isArray() || steps.isEmpty()) return ToolResult.error("'steps' must list at least one step");
    if (steps.size() > MAX_STEPS) return ToolResult.error("A plan may have at most " + MAX_STEPS + " steps");

    UUID userId = jizza.get().getUserId();
    ObjectNode payload = Json.obj();
    payload.put("planId", UUID.randomUUID().toString());
    payload.put("name", name);
    if (!args.path("description").asText("").isBlank()) {
      payload.put("description", args.path("description").asText().trim());
    }
    ArrayNode outSteps = payload.putArray("steps");
    Set<String> stepInputs = new LinkedHashSet<>();
    int i = 0;
    for (JsonNode step : steps) {
      i++;
      String kind = step.path("kind").asText("agent");
      String instruction = step.path("instruction").asText("").trim();
      if (instruction.isEmpty() && !"tool".equals(kind)) {
        return ToolResult.error("Step " + i + " needs an instruction");
      }
      ObjectNode out = outSteps.addObject();
      if (!step.path("why").asText("").isBlank()) out.put("why", step.path("why").asText().trim());
      if ("tool".equals(kind)) {
        String problem = toolStep(jizza.get(), i, step, instruction, out, stepInputs);
        if (problem != null) return ToolResult.error(problem);
        continue;
      }
      if ("ask".equals(kind)) {
        out.put("kind", "ask").put("instruction", instruction);
        continue;
      }
      if (!"agent".equals(kind)) {
        return ToolResult.error("Step " + i + ": kind must be tool, agent or ask");
      }
      String key = step.path("agent_key").asText("");
      Optional<AgentEntity> specialist = agents.findByUserIdAndKey(userId, key)
          .filter(a -> !a.isOrchestrator() && a.isEnabled());
      if (specialist.isEmpty()) {
        return ToolResult.error("Step " + i + ": no enabled specialist with key '" + key
            + "'. Available: " + roster(userId));
      }
      AgentEntity agent = specialist.get();
      boolean changes = step.path("changes_things").asBoolean(false);
      out.put("kind", "agent").put("instruction", instruction)
          .put("agentId", agent.getId().toString()).put("agentKey", agent.getKey())
          .put("agentName", agent.getName()).put("changesThings", changes);
      if (changes) approval(agent, out);
    }
    ArrayNode notes = payload.putArray("assumptions");
    for (JsonNode note : args.path("assumptions")) {
      if (notes.size() == 6) break;
      if (!note.asText("").isBlank()) notes.add(note.asText().trim());
    }

    // What to keep for reusing it next time: a draft for the card, where the user sets the
    // guardrails before anything is saved.
    var reuse = ReuseArgs.parse(args.path("reuse"));
    if (reuse != null) {
      reuse = withStepInputs(reuse, stepInputs);
      UUID conversationId = Activity.current()
          .map(ActivitySink::conversationId).orElse(null);
      payload.set("reuse", reuse.withLearnedFrom(
          new ReuseProfile.LearnedFrom(conversationId, null))
          .toJson());
    }

    recorder.record(new UiAction(UiAction.PROPOSE_WORKFLOW, payload));
    return ToolResult.ok(Json.obj().put("done", true).put("note",
        "The plan is on the user's screen with Approve and Change buttons. Nothing is saved until "
            + "they approve it. End your turn with one or two sentences; do not repeat the steps."));
  }

  /**
   * A step that makes one of the conversation's service calls again, exactly as it was made but
   * for the values mapped to inputs. Null when it was built, otherwise what is wrong with it.
   */
  private String toolStep(AgentEntity jizza, int index, JsonNode step, String instruction,
                          ObjectNode out, Set<String> inputs) {
    if (callSteps == null) return "Step " + index + ": tool steps are not available";
    CallSteps.Step built;
    try {
      built = callSteps.step(jizza.getUserId(), step.path("call").asInt(0), step.path("arguments"),
          step.path("inputs"));
    } catch (IllegalArgumentException e) {
      return "Step " + index + ": " + e.getMessage();
    }
    GeneratedToolEntity action = built.action();
    String label = action.getLabel() != null ? action.getLabel() : action.getName();
    out.put("kind", "tool").put("instruction", instruction.isEmpty() ? label : instruction)
        .put("toolId", action.getId().toString()).put("toolName", action.getName())
        .put("toolLabel", label).put("changesThings", action.isSideEffecting());
    if (built.call().agentName() != null) out.put("agentName", built.call().agentName());
    out.set("arguments", built.arguments());
    if (!built.inputs().isEmpty()) {
      ArrayNode reads = out.putArray("inputs");
      built.inputs().forEach(reads::add);
    }
    inputs.addAll(built.inputs());
    if (action.isSideEffecting()) {
      // The saved workflow runs as Jizza, so Jizza's rules for this action are the ones that apply.
      AutonomyGate g = gate.getIfAvailable();
      if (g == null) {
        out.put("approval", "ask");
      } else {
        Decision decision = g.evaluate(jizza.getId(), action.getName(), built.arguments());
        out.put("approval", decision.allowed() ? "auto" : decision.denied() ? "never" : "ask");
      }
    }
    return null;
  }

  /** Every input a step reads is one a run must be given, whether or not Jizza listed it. */
  private static ReuseProfile withStepInputs(ReuseProfile reuse, Set<String> used) {
    List<ReuseProfile.Input> inputs = new ArrayList<>(reuse.inputs());
    for (String name : used) {
      boolean listed = inputs.stream().anyMatch(in -> in.name().equals(name));
      if (!listed) inputs.add(new ReuseProfile.Input(name, null, true));
    }
    return new ReuseProfile(reuse.summary(), reuse.examples(), inputs, reuse.guardrails(),
        reuse.learnedFrom());
  }

  /** What the agent's autonomy rules would do today with a step that changes something. */
  private void approval(AgentEntity agent, ObjectNode out) {
    AutonomyGate g = gate.getIfAvailable();
    if (g == null) {
      out.put("approval", "ask");
      return;
    }
    Decision decision = g.evaluate(agent.getId(), SIDE_EFFECT_PROBE, null);
    out.put("approval", decision.allowed() ? "auto" : decision.denied() ? "never" : "ask");
  }

  private static ToolSpec spec(String roster) {
    ObjectNode step = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "kind", enumOf("tool: repeat a service call this conversation made, exactly (see "
                + "recent_service_calls); prefer it wherever a call did the work. agent: a "
                + "specialist does this step, for judgement. ask: stop and ask the user.",
            List.of("tool", "agent", "ask")),
        "call", JsonSchemas.integer("For a tool step, the call's number from "
            + "recent_service_calls"),
        "inputs", JsonSchemas.objectSchema("For a tool step, which arguments come from the "
            + "run's input instead of the value used today: {\"<argument>\": \"<input name>\"}"),
        "arguments", JsonSchemas.objectSchema("For a tool step, arguments to set differently, "
            + "e.g. {\"<argument>\": \"{{ nodes.step_1.<field> }}\"} to use step 1's result"),
        "agent_key", JsonSchemas.string("For an agent step, which specialist. One of: " + roster),
        "instruction", JsonSchemas.string("What the agent does, or what to ask the user, "
            + "written so it stands on its own. For a tool step, a short name for it"),
        "why", JsonSchemas.string("One short line on why this step is there"),
        "changes_things", JsonSchemas.bool(
            "True if the step changes something outside Jizza: sends a message, creates or "
                + "updates a record, places an order, moves money"))),
        List.of("kind"));
    ObjectNode steps = Json.obj().put("type", "array")
        .put("description", "The steps, in the order they run");
    steps.set("items", step);
    ObjectNode assumptions = Json.obj().put("type", "array")
        .put("description", "Defaults you chose that the user did not state, one short line each");
    assumptions.set("items", Json.obj().put("type", "string"));
    return new ToolSpec(PROPOSE,
        "Show the user a finished workflow plan to approve. Use it once you have talked the "
            + "request through with the relevant specialists and nothing is left that only the "
            + "user can decide, or to offer to save a job you just did that the user will want "
            + "again (fill in reuse). Approving saves it as a draft workflow; you never save it "
            + "yourself. For a task in the user's browser, use a saved recording if "
            + "find_saved_workflows has one that fits, or offer web_record so they can show it "
            + "once; never invent page elements or selectors.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "name", JsonSchemas.string("Short name, e.g. Gold rate checker"),
            "description", JsonSchemas.string("What the workflow does, in one or two sentences"),
            "steps", steps,
            "assumptions", assumptions,
            "reuse", ReuseArgs.schema())),
            List.of("name", "steps")));
  }

  private String roster(UUID userId) {
    return agents.findByUserIdAndIsOrchestratorFalseAndEnabledTrue(userId).stream()
        .map(a -> a.getKey() + " (" + a.getName()
            + (a.getDescription() != null ? ": " + a.getDescription()
                : a.getDomain() == null ? "" : ", " + a.getDomain()) + ")")
        .collect(Collectors.joining(", "));
  }

  private Optional<AgentEntity> orchestrator(UUID agentId) {
    return agents.findById(agentId).filter(AgentEntity::isOrchestrator);
  }

  private static ObjectNode enumOf(String description, List<String> values) {
    ObjectNode node = JsonSchemas.string(description);
    ArrayNode allowed = node.putArray("enum");
    values.forEach(allowed::add);
    return node;
  }
}
