package com.jizza.engine.orchestrator.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
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

  public WorkflowPlanToolProvider(AgentRepository agents, UiActionRecorder recorder,
                                  ObjectProvider<AutonomyGate> gate) {
    this.agents = agents;
    this.recorder = recorder;
    this.gate = gate;
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
    int i = 0;
    for (JsonNode step : steps) {
      i++;
      String kind = step.path("kind").asText("agent");
      String instruction = step.path("instruction").asText("").trim();
      if (instruction.isEmpty()) return ToolResult.error("Step " + i + " needs an instruction");
      ObjectNode out = outSteps.addObject();
      if (!step.path("why").asText("").isBlank()) out.put("why", step.path("why").asText().trim());
      if ("ask".equals(kind)) {
        out.put("kind", "ask").put("instruction", instruction);
        continue;
      }
      if (!"agent".equals(kind)) return ToolResult.error("Step " + i + ": kind must be agent or ask");
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

    recorder.record(new UiAction(UiAction.PROPOSE_WORKFLOW, payload));
    return ToolResult.ok(Json.obj().put("done", true).put("note",
        "The plan is on the user's screen with Approve and Change buttons. Nothing is saved until "
            + "they approve it. End your turn with one or two sentences; do not repeat the steps."));
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
        "kind", enumOf("agent: a specialist does this step. ask: stop and ask the user.",
            List.of("agent", "ask")),
        "agent_key", JsonSchemas.string("For an agent step, which specialist. One of: " + roster),
        "instruction", JsonSchemas.string(
            "What the agent does, or what to ask the user, written so it stands on its own"),
        "why", JsonSchemas.string("One short line on why this step is there"),
        "changes_things", JsonSchemas.bool(
            "True if the step changes something outside Jizza: sends a message, creates or "
                + "updates a record, places an order, moves money"))),
        List.of("kind", "instruction"));
    ObjectNode steps = Json.obj().put("type", "array")
        .put("description", "The steps, in the order they run");
    steps.set("items", step);
    ObjectNode assumptions = Json.obj().put("type", "array")
        .put("description", "Defaults you chose that the user did not state, one short line each");
    assumptions.set("items", Json.obj().put("type", "string"));
    return new ToolSpec(PROPOSE,
        "Show the user a finished workflow plan to approve. Use it only once you have talked the "
            + "request through with the relevant specialists and nothing is left that only the "
            + "user can decide. Approving saves it as a draft workflow; you never save it yourself.",
        JsonSchemas.object(new LinkedHashMap<>(Map.of(
            "name", JsonSchemas.string("Short name, e.g. Gold rate checker"),
            "description", JsonSchemas.string("What the workflow does, in one or two sentences"),
            "steps", steps,
            "assumptions", assumptions)), List.of("name", "steps")));
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
