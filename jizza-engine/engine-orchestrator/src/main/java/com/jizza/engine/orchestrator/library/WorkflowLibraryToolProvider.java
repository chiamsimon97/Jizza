package com.jizza.engine.orchestrator.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivitySink;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolProvider;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.orchestrator.ui.UiActionRecorder;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code find_saved_workflows} and {@code run_saved_workflow}: Jizza looks a request up in the
 * user's workflows and, when one does the job, runs it instead of working the job out again.
 *
 * <p>"With the user's knowledge" is enforced here rather than left to the prompt. A workflow whose
 * guardrails say to ask first (and every draft) runs only with a confirmation this tool handed out
 * on an earlier turn of the same conversation, redeemed on a later turn the user started by typing.
 * Jizza cannot ask and answer in the same turn, and an event-started turn cannot redeem one. The
 * question is remembered per workflow and conversation, so when the user agrees Jizza only has to
 * call again: chat history keeps what was said, not tool results, and a code Jizza had to carry
 * over would be lost. Every run Jizza starts is put on the reply, so the user sees which ran.
 */
@Component
public class WorkflowLibraryToolProvider implements ToolProvider {

  public static final String FIND = "find_saved_workflows";
  public static final String RUN = "run_saved_workflow";

  /** How long an offer to run stays redeemable. */
  static final Duration OFFER_TTL = Duration.ofMinutes(30);

  private final AgentRepository agents;
  private final ObjectProvider<WorkflowLibrary> library;
  private final UiActionRecorder recorder;
  private final Clock clock;
  private final List<ToolSpec> specs;

  /** Offers to run, by confirmation code. */
  private final Map<String, Offer> offers = new ConcurrentHashMap<>();

  record Offer(UUID userId, UUID workflowId, String name, UUID conversationId, int askedIn,
               Instant expires) {}

  /** A run Jizza asked the user about in a conversation and has not had an answer to yet. */
  public record Pending(UUID workflowId, String name) {}

  /** How many of the user's workflows a lookup that found nothing lists for Jizza to judge. */
  static final int CATALOG = 40;

  @Autowired
  public WorkflowLibraryToolProvider(AgentRepository agents, ObjectProvider<WorkflowLibrary> library,
                                     UiActionRecorder recorder) {
    this(agents, library, recorder, Clock.systemUTC());
  }

  WorkflowLibraryToolProvider(AgentRepository agents, ObjectProvider<WorkflowLibrary> library,
                              UiActionRecorder recorder, Clock clock) {
    this.agents = agents;
    this.library = library;
    this.recorder = recorder;
    this.clock = clock;
    this.specs = List.of(
        new ToolSpec(FIND,
            "Look a request up in the user's workflows (the ones saved for reuse and the ones they "
                + "built themselves). Use it whenever the user mentions a workflow, however "
                + "loosely (\"the one that books movie tickets\"), and before planning, building "
                + "or doing a multi-step job: if one already does it, run that one with "
                + "run_saved_workflow instead of working it out again. Matching forgives spelling "
                + "and wording; when nothing matches closely, the reply lists the user's "
                + "workflows so you can pick the one they mean by its meaning.",
            JsonSchemas.object(new LinkedHashMap<>(Map.of(
                "request", JsonSchemas.string("What the user wants done or the workflow they "
                    + "mean, in their words"))),
                List.of("request"))),
        new ToolSpec(RUN,
            "Run one of the user's workflows for this request, with its fixed steps. The first call "
                + "on a workflow that asks before running puts the question to the user; end your "
                + "turn. When the user agrees (they type yes or press the button), call this "
                + "again with the same workflow_id: the agreement is remembered for this "
                + "conversation, so no confirmation needs to be passed. Never tell the user you "
                + "are running a workflow without calling this tool in the same turn. Tell the "
                + "user which workflow you used.",
            JsonSchemas.object(new LinkedHashMap<>(Map.of(
                "workflow_id", JsonSchemas.string("From find_saved_workflows"),
                "input", JsonSchemas.objectSchema("Values for the workflow's inputs, by name"),
                "confirmation", JsonSchemas.string("Optional: the confirmation an earlier call "
                    + "returned, once the user has agreed"))),
                List.of("workflow_id"))));
  }

  @Override
  public List<ToolSpec> toolsFor(UUID agentId) {
    return library.getIfAvailable() != null && orchestrator(agentId).isPresent() ? specs : List.of();
  }

  @Override
  public boolean supports(UUID agentId, String toolName) {
    return (FIND.equals(toolName) || RUN.equals(toolName)) && orchestrator(agentId).isPresent();
  }

  @Override
  public ToolResult invoke(UUID agentId, ToolCall call) {
    Optional<AgentEntity> jizza = orchestrator(agentId);
    WorkflowLibrary lib = library.getIfAvailable();
    if (jizza.isEmpty() || lib == null) return ToolResult.error("The workflow library is not available");
    JsonNode args = call.args() == null ? Json.obj() : call.args();
    UUID userId = jizza.get().getUserId();
    return switch (call.name()) {
      case FIND -> find(lib, userId, args);
      case RUN -> run(lib, userId, args);
      default -> ToolResult.error("Unknown tool " + call.name());
    };
  }

  private ToolResult find(WorkflowLibrary lib, UUID userId, JsonNode args) {
    String request = args.path("request").asText("").strip();
    if (request.isEmpty()) return ToolResult.error("'request' must not be empty");
    List<WorkflowLibrary.Match> matches = lib.find(userId, request, WorkflowLibrary.Use.CHAT, 5);
    boolean loose = false;
    if (matches.isEmpty()) {
      matches = lib.search(userId, request, 5);
      loose = !matches.isEmpty();
    }
    ObjectNode out = Json.obj();
    ArrayNode list = out.putArray("workflows");
    matches.forEach(m -> list.add(ReuseArgs.describe(m)));
    if (matches.isEmpty() || loose) {
      // Words alone did not settle it: let Jizza judge by meaning from everything the user has.
      ArrayNode all = out.putArray("all_workflows");
      lib.list(userId).stream().limit(CATALOG).forEach(m -> {
        ObjectNode item = all.addObject().put("workflow_id", m.workflowId().toString())
            .put("name", m.name());
        if (m.description() != null && !m.description().isBlank()) {
          item.put("description", m.description());
        }
      });
    }
    out.put("note", matches.isEmpty() && out.path("all_workflows").isEmpty()
        ? "The user has no workflows Jizza can run. Go ahead as usual; if the job turns out to be "
            + "one the user will want again, offer to save it."
        : matches.isEmpty()
            ? "No workflow shares words with the request. Read all_workflows: if one of them is "
                + "clearly what the user means (same job, other words, another spelling or "
                + "language), use it with run_saved_workflow. If several could be, ask which one. "
                + "If none is, go ahead as usual."
            : loose
                ? "Only loose matches. Check them and all_workflows against what the user means; "
                    + "run the one that is clearly it with run_saved_workflow, or ask which one."
                : "If one of these does what the user asked, run it with run_saved_workflow rather "
                    + "than building something new, and follow its rules. If none really fits, "
                    + "go ahead as usual.");
    return ToolResult.ok(out);
  }

  private ToolResult run(WorkflowLibrary lib, UUID userId, JsonNode args) {
    UUID workflowId;
    try {
      workflowId = UUID.fromString(args.path("workflow_id").asText(""));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("'workflow_id' is not a workflow id; take it from find_saved_workflows");
    }
    Optional<WorkflowLibrary.Match> entry = lib.list(userId).stream()
        .filter(m -> m.workflowId().equals(workflowId)).findFirst();
    if (entry.isEmpty()) return ToolResult.error("That is not one of the user's workflows; look it up with find_saved_workflows");
    WorkflowLibrary.Match match = entry.get();
    JsonNode input = args.path("input").isObject() ? args.path("input") : Json.obj();

    Optional<ActivitySink> sink = Activity.current();
    UUID conversationId = sink.map(ActivitySink::conversationId).orElse(null);
    boolean needsAsking = match.profile().guardrails().confirmBeforeRun()
        || match.status() != com.jizza.engine.api.enums.WorkflowStatus.APPROVED;

    boolean confirmed = false;
    String code = args.path("confirmation").asText("").strip();
    if (code.isEmpty()) code = askedEarlier(userId, workflowId, conversationId, sink.orElse(null));
    if (!code.isEmpty()) {
      String problem = redeem(code, userId, workflowId, conversationId, sink.orElse(null));
      if (problem != null) return ToolResult.error(problem);
      confirmed = true;
    } else if (needsAsking) {
      return offer(match, userId, conversationId, sink.orElse(null));
    } else if (!Activity.fromUser()) {
      // Running without asking is for answering the user's own request, never an event's.
      return offer(match, userId, conversationId, sink.orElse(null));
    }

    RunDto run;
    try {
      run = lib.reuse(userId, workflowId, input, WorkflowLibrary.Use.CHAT,
          "chat:" + (conversationId == null ? "none" : conversationId), confirmed);
    } catch (RuntimeException e) {
      return ToolResult.error(e.getMessage());
    }
    String status = run.status().name().toLowerCase(Locale.ROOT);
    recorder.record(new UiAction(UiAction.REUSED_WORKFLOW, Json.obj()
        .put("workflowId", workflowId.toString()).put("name", match.name())
        .put("runId", run.id().toString()).put("status", status)));
    ObjectNode out = Json.obj().put("run_id", run.id().toString()).put("status", status);
    if (run.currentNode() != null) out.put("current_step", run.currentNode());
    out.put("note", "Started the saved workflow '" + match.name() + "'. Tell the user you used it "
        + "rather than building something new, and what happened; the run is on the Runs page.");
    return ToolResult.ok(out);
  }

  /** Puts the question to the user and hands Jizza the code to run it with once they agree. */
  private ToolResult offer(WorkflowLibrary.Match match, UUID userId, UUID conversationId,
                           ActivitySink sink) {
    purge();
    String code = UUID.randomUUID().toString().substring(0, 8);
    // One open question per workflow and conversation: asking again replaces the last one.
    offers.values().removeIf(o -> o.userId().equals(userId)
        && o.workflowId().equals(match.workflowId())
        && Objects.equals(o.conversationId(), conversationId));
    offers.put(code, new Offer(userId, match.workflowId(), match.name(), conversationId,
        identity(sink), clock.instant().plus(OFFER_TTL)));
    boolean onScreen = recorder.active();
    if (onScreen) {
      ObjectNode ask = Json.obj().put("question",
          "You already have a saved workflow for this, \"" + match.name() + "\". Run it?");
      ask.putArray("options").add("Yes, run " + match.name()).add("No, do it another way");
      recorder.record(new UiAction(UiAction.ASK_USER, ask));
    }
    return ToolResult.ok(Json.obj().put("needs_confirmation", true).put("confirmation", code)
        .put("note", (onScreen
            ? "The question is on the user's screen; do not ask it again. "
            : "Ask the user, in one sentence, whether to run '" + match.name() + "'. ")
            + "End your turn now, and do not say that you are running it. If they agree, call "
            + "run_saved_workflow again with the same workflow_id and inputs (the agreement is "
            + "remembered here). If any required input is unknown, ask for it in the same "
            + "sentence."));
  }

  private String redeem(String code, UUID userId, UUID workflowId, UUID conversationId,
                        ActivitySink sink) {
    purge();
    Offer offer = offers.get(code);
    if (offer == null || !offer.userId().equals(userId) || !offer.workflowId().equals(workflowId)
        || !Objects.equals(offer.conversationId(), conversationId)) {
      return "That confirmation is not valid for this workflow here; call without one to ask again";
    }
    if (offer.askedIn() == identity(sink)) {
      return "The user has not answered yet. End your turn and wait for them to agree";
    }
    if (!Activity.fromUser()) return "Only the user's own reply can confirm this run";
    offers.remove(code);
    return null;
  }

  /**
   * The code of the question Jizza already asked about this workflow in this conversation, on an
   * earlier turn, when this turn is the user's own reply; empty otherwise. The chat keeps only
   * what was said, not tool results, so Jizza would otherwise never see the code again and ask
   * the same question forever.
   */
  private String askedEarlier(UUID userId, UUID workflowId, UUID conversationId, ActivitySink sink) {
    if (conversationId == null || !Activity.fromUser()) return "";
    purge();
    int now = identity(sink);
    return offers.entrySet().stream()
        .filter(e -> e.getValue().userId().equals(userId)
            && e.getValue().workflowId().equals(workflowId)
            && conversationId.equals(e.getValue().conversationId())
            && e.getValue().askedIn() != now)
        .map(Map.Entry::getKey).findFirst().orElse("");
  }

  /** The runs Jizza asked the user about in this conversation that are still waiting on them. */
  public List<Pending> pending(UUID userId, UUID conversationId) {
    if (userId == null || conversationId == null) return List.of();
    purge();
    return offers.values().stream()
        .filter(o -> o.userId().equals(userId) && conversationId.equals(o.conversationId()))
        .map(o -> new Pending(o.workflowId(), o.name()))
        .distinct().toList();
  }

  private void purge() {
    Instant now = clock.instant();
    offers.values().removeIf(o -> o.expires().isBefore(now));
  }

  /** Which job (turn) a call is made in. */
  private static int identity(ActivitySink sink) {
    return sink == null ? 0 : System.identityHashCode(sink);
  }

  private Optional<AgentEntity> orchestrator(UUID agentId) {
    return agents.findById(agentId).filter(AgentEntity::isOrchestrator);
  }
}
