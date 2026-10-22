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
 * user's saved workflows and, when one does the job, runs it instead of working the job out again.
 *
 * <p>"With the user's knowledge" is enforced here rather than left to the prompt. A workflow whose
 * guardrails say to ask first (and every draft) runs only with a confirmation this tool handed out
 * on an earlier turn of the same conversation, redeemed on a later turn the user started by typing.
 * Jizza cannot ask and answer in the same turn, and an event-started turn cannot redeem one. Every
 * run Jizza starts is put on the reply, so the user sees which workflow ran.
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

  record Offer(UUID userId, UUID workflowId, UUID conversationId, int askedIn, Instant expires) {}

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
            "Look the request up in the user's saved workflows. Do this before planning, building "
                + "or doing a multi-step job: if a saved workflow already does it, run that one "
                + "with run_saved_workflow instead of working it out again.",
            JsonSchemas.object(new LinkedHashMap<>(Map.of(
                "request", JsonSchemas.string("What the user wants done, in their words"))),
                List.of("request"))),
        new ToolSpec(RUN,
            "Run one of the user's saved workflows for this request, with its fixed steps. The "
                + "first call on a workflow that asks before running puts the question to the "
                + "user and returns a confirmation; end your turn, and once the user agrees call "
                + "again with it. Tell the user which saved workflow you used.",
            JsonSchemas.object(new LinkedHashMap<>(Map.of(
                "workflow_id", JsonSchemas.string("From find_saved_workflows"),
                "input", JsonSchemas.objectSchema("Values for the workflow's inputs, by name"),
                "confirmation", JsonSchemas.string("The confirmation an earlier call returned, "
                    + "once the user has agreed"))),
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
    ObjectNode out = Json.obj();
    ArrayNode list = out.putArray("workflows");
    matches.forEach(m -> list.add(ReuseArgs.describe(m)));
    out.put("note", matches.isEmpty()
        ? "None of the user's saved workflows fits. Go ahead as usual; if the job turns out to be "
            + "one the user will want again, offer to save it."
        : "If one of these does what the user asked, run it with run_saved_workflow rather than "
            + "building something new, and follow its rules. If none really fits, go ahead as usual.");
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
    if (entry.isEmpty()) return ToolResult.error("That workflow is not in the user's saved workflows");
    WorkflowLibrary.Match match = entry.get();
    JsonNode input = args.path("input").isObject() ? args.path("input") : Json.obj();

    Optional<ActivitySink> sink = Activity.current();
    UUID conversationId = sink.map(ActivitySink::conversationId).orElse(null);
    boolean needsAsking = match.profile().guardrails().confirmBeforeRun()
        || match.status() != com.jizza.engine.api.enums.WorkflowStatus.APPROVED;

    boolean confirmed = false;
    String code = args.path("confirmation").asText("").strip();
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
    offers.put(code, new Offer(userId, match.workflowId(), conversationId, identity(sink),
        clock.instant().plus(OFFER_TTL)));
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
            + "End your turn. If they agree, call run_saved_workflow again with this confirmation "
            + "and the same inputs. If any required input is unknown, ask for it in the same "
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
