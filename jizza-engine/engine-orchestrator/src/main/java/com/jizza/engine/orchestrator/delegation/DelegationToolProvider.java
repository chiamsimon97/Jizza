package com.jizza.engine.orchestrator.delegation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivitySink;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.activity.JobCancelledException;
import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.agent.TeamTrail;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.cosmos.AccessContext;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.orchestrator.directory.DefaultAgentDirectory;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handing work down a team (R18/R19). Offered to Jizza and to any agent that has reports of its
 * own, and to nobody else, which is what stops a specialist from reaching sideways into another
 * domain or back up to its manager.
 *
 * <p>Each agent's roster is its direct reports only. Jizza may also hand a task straight to an
 * agent deeper in a team when the user names it; the managers above it are skipped, but their
 * locked guardrails still bind because the gate checks them in code. Results always come back to
 * the agent that asked -- a report's words never travel to the user unrelayed (R20).
 *
 * <p>One request carries a budget down the whole team: how deep it may go, how many agent runs it
 * may start and how many tokens it may spend. Running out ends that branch with a plain message
 * rather than an error, so the manager can still answer with what it has.
 */
@Component
public class DelegationToolProvider implements ToolProvider {

  private static final Logger log = LoggerFactory.getLogger(DelegationToolProvider.class);

  public static final String DELEGATE = "delegate_to_specialist";
  public static final String DELEGATE_MANY = "delegate_to_specialists";

  /** Agents named for direct hand-off in Jizza's tool description, at most. */
  private static final int MAX_NAMED_DEEPER = 25;

  /** One request's path through the team and what it has spent so far. */
  record Chain(List<UUID> path, AtomicInteger runs, AtomicLong tokens, boolean trial) {
    Chain then(UUID agentId) {
      List<UUID> next = new ArrayList<>(path);
      next.add(agentId);
      return new Chain(List.copyOf(next), runs, tokens, trial);
    }

    int depth() { return path.size() - 1; }
  }

  private static final ThreadLocal<Chain> CHAIN = new ThreadLocal<>();

  private final AgentRepository agents;
  private final Agent baseAgent;
  private final DelegationRecorder recorder;
  private final AgentProperties.Teams limits;
  private final ExecutorService fanOut =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jizza-delegate-", 0).factory());

  /** For tests: the default team limits. */
  public DelegationToolProvider(AgentRepository agents, Agent baseAgent, DelegationRecorder recorder) {
    this(agents, baseAgent, recorder, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public DelegationToolProvider(AgentRepository agents, Agent baseAgent, DelegationRecorder recorder,
                                AgentProperties props) {
    this.agents = agents;
    this.baseAgent = baseAgent;
    this.recorder = recorder;
    this.limits = props == null ? new AgentProperties.Teams() : props.getTeams();
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    if (coordinatingThreads(agentId)) return List.of();
    AgentEntity manager = agents.findById(agentId).orElse(null);
    if (manager == null || !delegates(manager)) return List.of();

    String roster = rosterFor(manager);
    String keyHelp = "Who to ask. One of: " + roster
        + (manager.isOrchestrator() ? deeperFor(manager) : "");
    ObjectNode oneSchema = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "agent_key", JsonSchemas.string(keyHelp),
        "task", JsonSchemas.string(
            "The self-contained task for that agent, including any context they need. "
                + "They cannot see the conversation."))),
        List.of("agent_key", "task"));

    ObjectNode item = JsonSchemas.object(new LinkedHashMap<>(Map.of(
        "agent_key", JsonSchemas.string(keyHelp),
        "task", JsonSchemas.string("The self-contained task for that agent"))),
        List.of("agent_key", "task"));
    ObjectNode list = Json.obj();
    list.put("type", "array");
    list.set("items", item);
    ObjectNode manySchema = JsonSchemas.object(
        new LinkedHashMap<>(Map.of("delegations", list)), List.of("delegations"));

    String who = manager.isOrchestrator() ? "specialist" : "member of your team";
    String prefer = manager.isOrchestrator()
        ? " Prefer a built-in specialist when it fits as well as a custom one." : "";
    return List.of(
        new ToolSpec(DELEGATE,
            "Ask one " + who + " to handle a task in their area and return their answer to you."
                + prefer,
            oneSchema),
        new ToolSpec(DELEGATE_MANY,
            "Ask several at once when a request spans their areas. They work in parallel and "
                + "all their answers come back to you to merge into one reply.",
            manySchema));
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    if (!DELEGATE.equals(toolName) && !DELEGATE_MANY.equals(toolName)) return false;
    if (coordinatingThreads(agentId)) return false;
    return agents.findById(agentId).map(this::delegates).orElse(false);
  }

  /**
   * Jizza answering in a main chat the user turned threads on for: there every specialist's topic
   * is worked on in that specialist's thread, so Jizza coordinates through threads and does not
   * relay a specialist in the chat itself.
   */
  static boolean coordinatingThreads(UUID agentId) {
    return ChatScope.current()
        .filter(s -> !s.thread() && s.threadsOn() && agentId != null && agentId.equals(s.responderAgentId()))
        .isPresent();
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    AgentEntity manager = agents.findById(agentId).orElse(null);
    if (manager == null || !delegates(manager)) {
      return ToolResult.error("Only an agent with a team may hand work on");
    }
    JsonNode args = call.args() == null ? Json.obj() : call.args();

    if (DELEGATE.equals(call.name())) {
      return delegateOne(manager, args.path("agent_key").asText(), args.path("task").asText());
    }
    if (DELEGATE_MANY.equals(call.name())) {
      return delegateMany(manager, args.path("delegations"));
    }
    return ToolResult.error("Unknown delegation tool " + call.name());
  }

  private ToolResult delegateOne(AgentEntity manager, String agentKey, String task) {
    Optional<AgentEntity> report = resolve(manager, agentKey);
    if (report.isEmpty()) {
      return ToolResult.error("No one with key '" + agentKey + "' on your team. Available: "
          + rosterFor(manager));
    }
    if (!report.get().isEnabled()) {
      return ToolResult.error(report.get().getName() + " is currently paused, so that part "
          + "of the request cannot be handled.");
    }
    Chain chain = chainFor(manager);
    Outcome outcome = run(manager, report.get(), task, parentRun(), chain);
    if (outcome.contribution() != null && manager.isOrchestrator()) {
      recorder.record(outcome.contribution());
    }
    return ToolResult.ok(outcome.node());
  }

  private ToolResult delegateMany(AgentEntity manager, JsonNode delegations) {
    if (!delegations.isArray() || delegations.isEmpty()) {
      return ToolResult.error("'delegations' must be a non-empty array");
    }
    record Job(AgentEntity report, String task, String requestedKey) {}
    List<Job> jobs = new ArrayList<>();
    for (JsonNode node : delegations) {
      String key = node.path("agent_key").asText();
      jobs.add(new Job(resolve(manager, key).orElse(null), node.path("task").asText(), key));
    }

    String trace = parentRun();
    Chain chain = chainFor(manager);
    // The job the turn reports to and the trail being kept, carried onto each fan-out thread.
    ActivitySink activity = Activity.current().orElse(null);
    TeamTrail trail = TeamTrail.current();
    var held = com.jizza.engine.api.agent.TrialRun.current();
    Semaphore slots = new Semaphore(Math.max(1, limits.getMaxParallel()));
    List<CompletableFuture<Outcome>> futures = jobs.stream()
        .map(job -> CompletableFuture.supplyAsync(() -> Activity.run(activity,
            () -> com.jizza.engine.api.agent.TrialRun.run(held != null, held,
            () -> TeamTrail.runWith(trail, () -> {
              if (job.report() == null) {
                return Outcome.problem(job.requestedKey(), null, "no one with that key on your team");
              }
              if (!job.report().isEnabled()) {
                return Outcome.problem(job.requestedKey(), job.report().getName(),
                    "this agent is paused");
              }
              slots.acquireUninterruptibly();
              try {
                return run(manager, job.report(), job.task(), trace, chain);
              } finally {
                slots.release();
              }
            }))), fanOut))
        .toList();

    ArrayNode results = Json.mapper().createArrayNode();
    for (CompletableFuture<Outcome> future : futures) {
      try {
        Outcome outcome = future.join();
        results.add(outcome.node());
        // Recorded here, on the turn's own thread: the recorder is turn-scoped and the fan-out
        // runs on separate threads, so contributions travel back rather than being written there.
        if (outcome.contribution() != null && manager.isOrchestrator()) {
          recorder.record(outcome.contribution());
        }
      } catch (java.util.concurrent.CompletionException e) {
        if (e.getCause() instanceof JobCancelledException cancelled) throw cancelled;
        log.warn("A parallel delegation failed", e);
        results.add(Json.obj().put("error", String.valueOf(e.getCause().getMessage())));
      } catch (RuntimeException e) {
        log.warn("A parallel delegation failed", e);
        ObjectNode error = Json.obj();
        error.put("error", e.getMessage());
        results.add(error);
      }
    }
    return ToolResult.ok(results);
  }

  /** What a delegation produced: the payload for the manager, and the attribution for the turn. */
  private record Outcome(ObjectNode node, DelegationRecorder.Contribution contribution) {
    static Outcome problem(String key, String name, String message) {
      ObjectNode node = Json.obj();
      node.put("agent_key", key);
      if (name != null) node.put("agent_name", name);
      node.put("error", message);
      return new Outcome(node, null);
    }
  }

  /** The run delegating, so the report's memory reads are stamped with the same chat turn. */
  private static String parentRun() {
    return AccessContext.current().map(AccessContext::toTrace).orElse(null);
  }

  /** The request's chain so far, or a new one starting at this manager. */
  private Chain chainFor(AgentEntity manager) {
    Chain chain = CHAIN.get();
    if (chain != null && !chain.path().isEmpty()
        && chain.path().get(chain.path().size() - 1).equals(manager.getId())) {
      return chain;
    }
    return new Chain(List.of(manager.getId()), new AtomicInteger(1), new AtomicLong(),
        com.jizza.engine.api.agent.TrialRun.active());
  }

  /**
   * Runs the report as an attended turn when a user is waiting on the other end of the chat; a
   * workflow step that started the chain is autonomous all the way down.
   */
  private Outcome run(AgentEntity manager, AgentEntity report, String task, String trace,
                      Chain chain) {
    String refusal = refusal(report, chain);
    if (refusal != null) return Outcome.problem(report.getKey(), report.getName(), refusal);

    Chain next = chain.then(report.getId());
    int depth = next.depth();
    // The two agents' exchange is shown in the Activity panel as a work log. It never reaches the
    // chat: only Jizza speaks there.
    Activity.emit(ActivityTypes.AGENT_MESSAGE, manager.getId(), manager.getName(), p -> p
        .put("toAgentId", report.getId().toString()).put("toAgentName", report.getName())
        .put("text", task).put("kind", "task").put("depth", depth));
    ObjectNode node = Json.obj();
    node.put("agent_key", report.getKey());
    node.put("agent_name", report.getName());
    String summary;
    boolean failed = false;
    int tokens = 0;
    TeamTrail.Recorded<AgentResult> recorded = null;
    Chain previous = CHAIN.get();
    CHAIN.set(next);
    try {
      recorded = TeamTrail.record(() -> baseAgent.run(new AgentRequest(report.getUserId(),
          report.getId(), task, null, List.of(), tierOf(report), false, trace, chain.trial())));
      AgentResult result = recorded.value();
      tokens = result.inputTokens() + result.outputTokens();
      chain.tokens().addAndGet(tokens);
      node.put("answer", result.text());
      if (result.truncatedByBudget()) node.put("truncated", true);
      summary = result.text();
      int spent = tokens;
      Activity.emit(ActivityTypes.AGENT_MESSAGE, report.getId(), report.getName(), p -> p
          .put("toAgentId", manager.getId().toString()).put("toAgentName", manager.getName())
          .put("text", result.text() == null ? "" : result.text()).put("kind", "answer")
          .put("depth", depth).put("tokens", spent));
    } catch (JobCancelledException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn("Agent {} failed on a delegated task", report.getKey(), e);
      node.put("error", e.getMessage());
      summary = "failed: " + e.getMessage();
      failed = true;
      Activity.emit(ActivityTypes.AGENT_MESSAGE, report.getId(), report.getName(), p -> p
          .put("toAgentId", manager.getId().toString()).put("toAgentName", manager.getName())
          .put("text", "Could not finish: " + e.getMessage()).put("kind", "failed")
          .put("depth", depth));
    } finally {
      if (previous == null) CHAIN.remove(); else CHAIN.set(previous);
    }

    // Who the report handed work to in turn, so the manager -- and the user -- can see the trail.
    if (recorded != null && !recorded.steps().isEmpty()) {
      ArrayNode team = node.putArray("team");
      for (TeamTrail.Step step : recorded.steps()) {
        team.addObject().put("from", step.fromName()).put("to", step.toName())
            .put("answer", abbreviate(step.answer())).put("failed", step.failed());
      }
    }
    TeamTrail.add(new TeamTrail.Step(manager.getId(), manager.getName(), report.getId(),
        report.getName(), depth, task, summary, failed, tokens));
    if (recorded != null) recorded.steps().forEach(TeamTrail::add);
    return new Outcome(node, new DelegationRecorder.Contribution(report.getId(),
        report.getName(), task, summary));
  }

  /** Why this hand-off may not start, or null when it may. */
  private String refusal(AgentEntity report, Chain chain) {
    if (chain.path().contains(report.getId())) {
      return "already working on this request, so it can't be asked again";
    }
    if (chain.depth() + 1 > limits.getMaxDepth()) {
      return "this request has gone as deep into the team as it may";
    }
    if (chain.runs().incrementAndGet() > limits.getMaxRunsPerRequest()) {
      return "this request has asked as many agents as it may; answer with what you have";
    }
    if (chain.tokens().get() >= limits.getMaxTokensPerRequest()) {
      return "this request has used its whole budget; answer with what you have";
    }
    return null;
  }

  static ModelTier tierOf(AgentEntity agent) {
    return "deep".equalsIgnoreCase(agent.getModelTier()) ? ModelTier.DEEP : ModelTier.STANDARD;
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    fanOut.shutdown();
  }

  /**
   * A direct report of the manager, or for Jizza also any agent in its teams, by key. Never Jizza
   * itself, never the manager's own managers.
   */
  private Optional<AgentEntity> resolve(AgentEntity manager, String agentKey) {
    if (agentKey == null || agentKey.isBlank()) return Optional.empty();
    Optional<AgentEntity> found = agents.findByUserIdAndKey(manager.getUserId(), agentKey)
        .filter(a -> !a.isOrchestrator())
        .filter(a -> !a.getId().equals(manager.getId()));
    if (found.isEmpty()) return Optional.empty();
    AgentEntity target = found.get();
    if (manager.getId().equals(target.getParentAgentId())) return found;
    if (manager.isOrchestrator() && target.isActive()) return found;
    return Optional.empty();
  }

  /** Jizza always delegates; any other agent only once it has someone reporting to it. */
  private boolean delegates(AgentEntity agent) {
    if (agent.isOrchestrator()) return true;
    if (!agent.isActive()) return false;
    return agents.findByParentAgentIdOrderByNameAsc(agent.getId()).stream()
        .anyMatch(r -> r.isEnabled() && !r.isOrchestrator());
  }

  private String rosterFor(AgentEntity manager) {
    List<AgentEntity> reports = directReports(manager);
    return reports.stream().map(r -> describe(r)).collect(Collectors.joining("; "));
  }

  /** Jizza's own reports. Rows from before teams existed have no manager, so they count as its. */
  private List<AgentEntity> directReports(AgentEntity manager) {
    List<AgentEntity> reports = new ArrayList<>(agents.findByParentAgentIdOrderByNameAsc(
        manager.getId()));
    if (manager.isOrchestrator()) {
      for (AgentEntity a : agents.findByUserIdAndIsOrchestratorFalseAndEnabledTrue(
          manager.getUserId())) {
        if (a.getParentAgentId() == null && reports.stream().noneMatch(r -> r.getId().equals(a.getId()))) {
          reports.add(a);
        }
      }
    }
    return reports.stream().filter(AgentEntity::isEnabled).filter(r -> !r.isOrchestrator())
        .toList();
  }

  private String describe(AgentEntity agent) {
    StringBuilder sb = new StringBuilder(agent.getKey()).append(": ").append(agent.getName());
    if (agent.isCustom()) sb.append(" (custom)");
    String about = agent.getDescription() != null && !agent.getDescription().isBlank()
        ? agent.getDescription() : agent.getDomain();
    if (about != null && !about.isBlank()) sb.append(". Use when: ").append(about.trim());
    if (agent.getResponsibilities().length > 0) {
      sb.append(" Responsibilities: ").append(String.join(", ", agent.getResponsibilities()));
    }
    List<AgentEntity> team = agents.findByParentAgentIdOrderByNameAsc(agent.getId()).stream()
        .filter(AgentEntity::isEnabled).toList();
    if (!team.isEmpty()) {
      sb.append(" Leads: ").append(team.stream().map(AgentEntity::getName)
          .collect(Collectors.joining(", ")));
    }
    return sb.toString();
  }

  /** Agents deeper in Jizza's teams, which it may name only when the user asked for one of them. */
  private String deeperFor(AgentEntity jizza) {
    List<UUID> direct = directReports(jizza).stream().map(AgentEntity::getId).toList();
    List<String> deeper = agents.findByUserIdAndIsOrchestratorFalseAndEnabledTrue(jizza.getUserId())
        .stream()
        .filter(a -> !direct.contains(a.getId()))
        .limit(MAX_NAMED_DEEPER)
        .map(a -> a.getKey() + " (" + a.getName() + ", under "
            + DefaultAgentDirectory.managers(agents, a.getId()).stream().findFirst()
                .map(AgentEntity::getName).orElse("Jizza") + ")")
        .toList();
    if (deeper.isEmpty()) return "";
    return ". Only when the user names one of them, you may also ask directly: "
        + String.join(", ", deeper);
  }

  private static String abbreviate(String text) {
    if (text == null) return null;
    return text.length() <= 400 ? text : text.substring(0, 400) + "…";
  }
}
