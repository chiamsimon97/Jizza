package com.jizza.engine.orchestrator.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.chat.ChatShortcut;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.util.FuzzyText;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Short follow-ups about a workflow run started from this conversation, answered at once with no
 * model call: "stop it", "how is it going?", and "what's the PNR?" once it has finished. Only a
 * run started in this same chat in the last day counts, and only when the message is plainly one
 * of these; anything else goes on to Jizza.
 */
@Component
@Order(10)
public class ChatRunCommands implements ChatShortcut {

  /** How long after it started a run still counts as "it" in its chat. */
  static final Duration RECENT = Duration.ofHours(24);
  private static final Set<RunStatus> GOING =
      Set.of(RunStatus.RUNNING, RunStatus.WAITING, RunStatus.PAUSED_WAITING_INSTRUCTION);

  private static final Pattern STOP = Pattern.compile(
      "^(please\\s+)?(cancel|stop|abort|halt|kill)(\\s+(it|that|this|the\\s+run|the\\s+workflow|running|the\\s+booking|now))*"
          + "(\\s+please)?[.!]*$", Pattern.CASE_INSENSITIVE);
  private static final Pattern STATUS = Pattern.compile(
      "^(status|progress|any\\s+news|(what('?s|\\s+is)\\s+the\\s+(status|progress))"
          + "|(how('?s|\\s+is)\\s+(it|the\\s+run)\\s+going)|(where('?s|\\s+is)\\s+it(\\s+(at|now))?)"
          + "|(is\\s+it\\s+(done|finished)(\\s+yet)?))\\s*[?.!]*$", Pattern.CASE_INSENSITIVE);
  /** "What's the PNR?", "what was the booking id": a question about something the run gave back. */
  private static final Pattern ASK_OUTPUT = Pattern.compile(
      "^(what('?s|\\s+is|\\s+was)|tell\\s+me|give\\s+me|show\\s+me)\\b(.*?)\\??$", Pattern.CASE_INSENSITIVE);

  private final WorkflowRunRepository runs;
  private final WorkflowRepository workflows;
  private final ObjectProvider<WorkflowEngine> engine;

  public ChatRunCommands(WorkflowRunRepository runs, WorkflowRepository workflows,
                         ObjectProvider<WorkflowEngine> engine) {
    this.runs = runs;
    this.workflows = workflows;
    this.engine = engine;
  }

  @Override
  public boolean slashOnly() {
    return false;
  }

  @Override
  public Optional<Orchestrator.Turn> handle(UUID userId, UUID conversationId, String text) {
    if (conversationId == null || text == null) return Optional.empty();
    String message = text.strip();
    if (message.isEmpty() || message.length() > 80) return Optional.empty();
    boolean stop = STOP.matcher(message).matches();
    boolean status = !stop && STATUS.matcher(message).matches();
    var asked = !stop && !status ? ASK_OUTPUT.matcher(message) : null;
    if (!stop && !status && (asked == null || !asked.matches())) return Optional.empty();

    WorkflowRunEntity run = latest(userId, conversationId);
    if (run == null) return Optional.empty();
    String name = workflows.findById(run.getWorkflowId()).map(WorkflowEntity::getName).orElse("the workflow");

    if (stop) {
      if (!GOING.contains(run.getStatus())) {
        return Optional.of(reply("**" + name + "** isn't running any more (" + statusWords(run.getStatus())
            + "), so there's nothing to stop."));
      }
      WorkflowEngine e = engine.getIfAvailable();
      if (e == null) return Optional.empty();
      e.cancel(run.getId());
      return Optional.of(reply("Stopped **" + name + "**. Anything it was waiting on you for is closed too."));
    }
    if (status) {
      return Optional.of(new Orchestrator.Turn(progress(run, name), List.of(),
          List.of(ran(run, name, run.getStatus().name().toLowerCase(java.util.Locale.ROOT))), 0, 0));
    }
    // A question about a value: only when the finished run gave back something with that name.
    if (run.getStatus() != RunStatus.DONE) return Optional.empty();
    WorkflowEngine e = engine.getIfAvailable();
    if (e == null) return Optional.empty();
    RunDto dto = e.get(run.getId());
    JsonNode result = dto.context() == null ? null : dto.context().path("result");
    if (result == null || !result.isObject() || result.isEmpty()) return Optional.empty();
    List<String> found = answers(asked.group(3), result);
    if (found.isEmpty()) return Optional.empty();
    return Optional.of(reply("From **" + name + "**:\n\n" + String.join("\n", found)));
  }

  /** The newest run this conversation started within {@link #RECENT}; null when there is none. */
  private WorkflowRunEntity latest(UUID userId, UUID conversationId) {
    Instant since = Instant.now().minus(RECENT);
    return runs.findTop5ByUserIdAndTriggerSourceEndingWithOrderByCreatedAtDesc(userId, "chat:" + conversationId)
        .stream().filter(r -> r.getCreatedAt() == null || r.getCreatedAt().isAfter(since))
        .findFirst().orElse(null);
  }

  /** The run's outputs whose names the question shares a word with, as "- **name**: value" lines. */
  static List<String> answers(String question, JsonNode result) {
    Map<String, String> asked = FuzzyText.terms(question == null ? "" : question);
    List<String> out = new ArrayList<>();
    if (asked.isEmpty()) return out;
    result.fields().forEachRemaining(e -> {
      String spaced = e.getKey().replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
      boolean meets = FuzzyText.terms(spaced).keySet().stream()
          .anyMatch(stem -> FuzzyText.nearAny(stem, asked.keySet()));
      if (meets && out.size() < 5) {
        JsonNode v = e.getValue();
        String shown = v.isValueNode() ? v.asText() : v.toString();
        out.add("- **" + e.getKey() + "**: " + (shown.length() > 400 ? shown.substring(0, 400) + "…" : shown));
      }
    });
    return out;
  }

  private String progress(WorkflowRunEntity run, String name) {
    String step = stepLabel(run);
    return switch (run.getStatus()) {
      case RUNNING -> "**" + name + "** is running" + (step == null ? "." : ", on \"" + step + "\".");
      case WAITING -> "**" + name + "** is waiting" + (step == null ? "" : " at \"" + step + "\"")
          + " and carries on by itself.";
      case PAUSED_WAITING_INSTRUCTION -> "**" + name + "** is waiting for you"
          + (step == null ? "" : " at \"" + step + "\"") + ". Answer the card in this chat or in Notifications.";
      case DONE -> "**" + name + "** has finished.";
      case FAILED -> "**" + name + "** failed" + (run.getError() == null ? "" : ": " + run.getError())
          + ". The run log has the details.";
      case CANCELLED -> "**" + name + "** was stopped.";
    };
  }

  private String stepLabel(WorkflowRunEntity run) {
    String node = run.getCurrentNode();
    if (node == null) return null;
    JsonNode graph = workflows.findById(run.getWorkflowId()).map(WorkflowEntity::getGraph).orElse(null);
    if (graph == null) return null;
    for (JsonNode n : graph.path("nodes")) {
      if (node.equals(n.path("id").asText())) {
        String label = n.path("label").asText("");
        return label.isBlank() ? null : label;
      }
    }
    return null;
  }

  private static String statusWords(RunStatus status) {
    return switch (status) {
      case DONE -> "it has finished";
      case FAILED -> "it failed";
      case CANCELLED -> "it was stopped";
      default -> "it is " + status.name().toLowerCase(java.util.Locale.ROOT);
    };
  }

  private static UiAction ran(WorkflowRunEntity run, String name, String status) {
    return new UiAction(UiAction.REUSED_WORKFLOW, Json.obj()
        .put("workflowId", run.getWorkflowId().toString()).put("name", name)
        .put("runId", run.getId().toString()).put("status", status).put("followUp", true));
  }

  private static Orchestrator.Turn reply(String text) {
    return new Orchestrator.Turn(text, List.of(), List.of(), 0, 0);
  }
}
