package com.jizza.engine.orchestrator.delegation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Jizza's delegation tools (R18/R19). Offered to the orchestrator agent and to nobody else, which
 * is what stops a specialist from re-delegating or reaching sideways into another domain.
 *
 * <p>Two shapes: one specialist at a time, and a fan-out that runs several concurrently for a
 * cross-domain request. Either way the results come back to Jizza to merge -- a specialist's words
 * never travel to the user unrelayed (R20).
 */
@Component
public class DelegationToolProvider implements ToolProvider {

  private static final Logger log = LoggerFactory.getLogger(DelegationToolProvider.class);

  public static final String DELEGATE = "delegate_to_specialist";
  public static final String DELEGATE_MANY = "delegate_to_specialists";

  private final AgentRepository agents;
  private final Agent baseAgent;
  private final DelegationRecorder recorder;
  private final ExecutorService fanOut =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jizza-delegate-", 0).factory());

  public DelegationToolProvider(AgentRepository agents, Agent baseAgent, DelegationRecorder recorder) {
    this.agents = agents;
    this.baseAgent = baseAgent;
    this.recorder = recorder;
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    if (!isOrchestrator(agentId)) return List.of();

    String roster = rosterFor(agentId);
    ObjectNode oneSchema = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "agent_key", JsonSchemas.string("Which specialist to ask. One of: " + roster),
        "task", JsonSchemas.string(
            "The self-contained task for that specialist, including any context they need. "
                + "They cannot see the conversation."))),
        List.of("agent_key", "task"));

    ObjectNode item = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "agent_key", JsonSchemas.string("Which specialist to ask. One of: " + roster),
        "task", JsonSchemas.string("The self-contained task for that specialist"))),
        List.of("agent_key", "task"));
    ObjectNode list = Json.obj();
    list.put("type", "array");
    list.set("items", item);
    ObjectNode manySchema = JsonSchemas.object(
        new LinkedHashMap<>(Map.of("delegations", list)), List.of("delegations"));

    return List.of(
        new ToolSpec(DELEGATE,
            "Ask one specialist to handle a task in their domain and return their answer to you.",
            oneSchema),
        new ToolSpec(DELEGATE_MANY,
            "Ask several specialists at once when a request spans domains. They run in parallel and "
                + "all their answers come back to you to merge into one reply.",
            manySchema));
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return (DELEGATE.equals(toolName) || DELEGATE_MANY.equals(toolName)) && isOrchestrator(agentId);
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    AgentEntity orchestrator = agents.findById(agentId).orElse(null);
    if (orchestrator == null || !orchestrator.isOrchestrator()) {
      return ToolResult.error("Only the orchestrator may delegate");
    }
    JsonNode args = call.args() == null ? Json.obj() : call.args();

    if (DELEGATE.equals(call.name())) {
      return delegateOne(orchestrator, args.path("agent_key").asText(), args.path("task").asText());
    }
    if (DELEGATE_MANY.equals(call.name())) {
      return delegateMany(orchestrator, args.path("delegations"));
    }
    return ToolResult.error("Unknown delegation tool " + call.name());
  }

  private ToolResult delegateOne(AgentEntity orchestrator, String agentKey, String task) {
    Optional<AgentEntity> specialist = resolve(orchestrator.getUserId(), agentKey);
    if (specialist.isEmpty()) {
      return ToolResult.error("No specialist with key '" + agentKey + "'. Available: "
          + rosterFor(orchestrator.getId()));
    }
    if (!specialist.get().isEnabled()) {
      return ToolResult.error(specialist.get().getName() + " is currently disabled, so that part "
          + "of the request cannot be handled.");
    }
    Outcome outcome = run(specialist.get(), task);
    if (outcome.contribution() != null) recorder.record(outcome.contribution());
    return ToolResult.ok(outcome.node());
  }

  private ToolResult delegateMany(AgentEntity orchestrator, JsonNode delegations) {
    if (!delegations.isArray() || delegations.isEmpty()) {
      return ToolResult.error("'delegations' must be a non-empty array");
    }
    record Job(AgentEntity specialist, String task, String requestedKey) {}
    List<Job> jobs = new ArrayList<>();
    for (JsonNode node : delegations) {
      String key = node.path("agent_key").asText();
      jobs.add(new Job(resolve(orchestrator.getUserId(), key).orElse(null),
          node.path("task").asText(), key));
    }

    List<CompletableFuture<Outcome>> futures = jobs.stream()
        .map(job -> CompletableFuture.supplyAsync(() -> {
          if (job.specialist() == null) {
            return Outcome.problem(job.requestedKey(), null, "no specialist with that key");
          }
          if (!job.specialist().isEnabled()) {
            return Outcome.problem(job.requestedKey(), job.specialist().getName(),
                "this specialist is disabled");
          }
          return run(job.specialist(), job.task());
        }, fanOut))
        .toList();

    ArrayNode results = Json.mapper().createArrayNode();
    for (CompletableFuture<Outcome> future : futures) {
      try {
        Outcome outcome = future.join();
        results.add(outcome.node());
        // Recorded here, on the turn's own thread: the recorder is turn-scoped and the fan-out
        // runs on separate threads, so contributions travel back rather than being written there.
        if (outcome.contribution() != null) recorder.record(outcome.contribution());
      } catch (RuntimeException e) {
        log.warn("A parallel delegation failed", e);
        ObjectNode error = Json.obj();
        error.put("error", e.getMessage());
        results.add(error);
      }
    }
    return ToolResult.ok(results);
  }

  /** What a delegation produced: the payload for Jizza, and the attribution for the turn. */
  private record Outcome(ObjectNode node, DelegationRecorder.Contribution contribution) {
    static Outcome problem(String key, String name, String message) {
      ObjectNode node = Json.obj();
      node.put("agent_key", key);
      if (name != null) node.put("agent_name", name);
      node.put("error", message);
      return new Outcome(node, null);
    }
  }

  /**
   * Runs the specialist as an attended turn: a user is waiting on the other end of this chat, so
   * this is not autonomous activity even though a model initiated it.
   */
  private Outcome run(AgentEntity specialist, String task) {
    ObjectNode node = Json.obj();
    node.put("agent_key", specialist.getKey());
    node.put("agent_name", specialist.getName());
    String summary;
    try {
      AgentResult result = baseAgent.run(new AgentRequest(specialist.getUserId(), specialist.getId(),
          task, null, List.of(), ModelTier.STANDARD, false, null));
      node.put("answer", result.text());
      if (result.truncatedByBudget()) node.put("truncated", true);
      summary = result.text();
    } catch (RuntimeException e) {
      log.warn("Specialist {} failed on a delegated task", specialist.getKey(), e);
      node.put("error", e.getMessage());
      summary = "failed: " + e.getMessage();
    }
    return new Outcome(node, new DelegationRecorder.Contribution(specialist.getId(),
        specialist.getName(), task, summary));
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    fanOut.shutdown();
  }

  private Optional<AgentEntity> resolve(UUID userId, String agentKey) {
    if (agentKey == null || agentKey.isBlank()) return Optional.empty();
    return agents.findByUserIdAndKey(userId, agentKey)
        .filter(a -> !a.isOrchestrator());
  }

  private boolean isOrchestrator(UUID agentId) {
    return agents.findById(agentId).map(AgentEntity::isOrchestrator).orElse(false);
  }

  private String rosterFor(UUID orchestratorId) {
    return agents.findById(orchestratorId)
        .map(a -> agents.findByUserIdAndIsOrchestratorFalseAndEnabledTrue(a.getUserId()).stream()
            .map(s -> s.getKey() + " (" + s.getName()
                + (s.getDomain() == null ? "" : ", " + s.getDomain()) + ")")
            .collect(Collectors.joining(", ")))
        .orElse("");
  }
}
