package com.jizza.engine.orchestrator.library;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.chat.ChatShortcut;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code /run <workflow name>}: runs one of the user's saved workflows straight away, with no model
 * call, so it works however much of Jizza's budget is left. Typing the command is the user's
 * go-ahead, the same as pressing Run on the workflow; the workflow's own limits (turned off, runs
 * per day, required inputs) still apply.
 *
 * <p>Inputs go on the lines after the command, one {@code name: value} per line:
 * <pre>
 * /run Ticket booking
 * movie: Ramayana
 * </pre>
 * A name that matches no workflow exactly runs the only workflow whose name contains it; when
 * several could be meant, the reply offers them as choices, each a {@code /run} of its own.
 */
@Component
public class RunWorkflowShortcut implements ChatShortcut {

  static final String COMMAND = "/run";
  private static final Pattern RUN = Pattern.compile("^/run(?:\\s+(.*))?$", Pattern.CASE_INSENSITIVE);
  private static final Pattern INPUT = Pattern.compile("^\\s*([A-Za-z_][\\w .-]*?)\\s*[:=]\\s*(.*)$");
  private static final int CHOICES = 8;

  private final ObjectProvider<WorkflowLibrary> library;

  public RunWorkflowShortcut(ObjectProvider<WorkflowLibrary> library) {
    this.library = library;
  }

  @Override
  public Optional<Orchestrator.Turn> handle(UUID userId, UUID conversationId, String text) {
    if (text == null) return Optional.empty();
    String[] lines = text.strip().split("\\R");
    Matcher m = RUN.matcher(lines[0].strip());
    if (!m.matches()) return Optional.empty();
    WorkflowLibrary lib = library.getIfAvailable();
    if (lib == null) return Optional.of(reply("Workflows are not available here."));

    String asked = clean(m.group(1));
    List<WorkflowLibrary.Match> all = lib.runnable(userId);
    if (asked.isEmpty()) {
      if (all.isEmpty()) return Optional.of(reply("You have no workflows to run yet."));
      return Optional.of(choose("Which workflow should I run?", all));
    }

    List<WorkflowLibrary.Match> exact = all.stream().filter(w -> key(w.name()).equals(key(asked))).toList();
    List<WorkflowLibrary.Match> found = !exact.isEmpty() ? exact
        : all.stream().filter(w -> key(w.name()).contains(key(asked))).toList();
    if (found.isEmpty()) {
      List<WorkflowLibrary.Match> near = lib.search(userId, asked, CHOICES);
      if (near.isEmpty()) {
        return Optional.of(all.isEmpty()
            ? reply("You have no workflows to run yet.")
            : choose("I couldn't find a workflow called \"" + asked + "\". Which one did you mean?", all));
      }
      return Optional.of(choose("I couldn't find a workflow called \"" + asked + "\". Did you mean one of these?", near));
    }
    // Two workflows with the very same name: the newest is the one people mean.
    if (found.size() > 1 && exact.isEmpty()) {
      return Optional.of(choose("More than one workflow matches \"" + asked + "\". Which one?", found));
    }
    WorkflowLibrary.Match workflow = found.get(0);
    return Optional.of(run(lib, userId, conversationId, workflow, inputs(lines), exact.size() > 1));
  }

  private Orchestrator.Turn run(WorkflowLibrary lib, UUID userId, UUID conversationId,
                                WorkflowLibrary.Match workflow, Map<String, String> given,
                                boolean newestOfSeveral) {
    ReuseProfile profile = workflow.profile();
    List<ReuseProfile.Input> declared = profile == null ? List.of() : profile.inputs();
    ObjectNode input = Json.obj();
    List<String> unknown = new ArrayList<>();
    given.forEach((name, value) -> {
      String target = declared.stream().map(ReuseProfile.Input::name)
          .filter(n -> n.equalsIgnoreCase(name.strip())).findFirst().orElse(null);
      if (target != null) input.put(target, value);
      else unknown.add(name);
    });
    List<ReuseProfile.Input> missing = declared.stream().filter(ReuseProfile.Input::required)
        .filter(i -> input.path(i.name()).asText("").isBlank()).toList();
    if (!missing.isEmpty()) {
      StringBuilder ask = new StringBuilder("\"").append(workflow.name()).append("\" needs ")
          .append(missing.size() == 1 ? "one value" : missing.size() + " values")
          .append(" before it can run. Send it again with them on the lines below the command:\n\n```\n")
          .append(COMMAND).append(' ').append(workflow.name());
      for (ReuseProfile.Input i : declared) {
        ask.append('\n').append(i.name()).append(": ").append(input.path(i.name()).asText(""));
      }
      ask.append("\n```");
      List<String> described = missing.stream().filter(i -> i.description() != null && !i.description().isBlank())
          .map(i -> "- **" + i.name() + "**: " + i.description()).toList();
      if (!described.isEmpty()) ask.append("\n\n").append(String.join("\n", described));
      return reply(ask.toString());
    }

    RunDto run;
    try {
      run = lib.reuse(userId, workflow.workflowId(), input, WorkflowLibrary.Use.CHAT,
          "chat:" + (conversationId == null ? "none" : conversationId), true);
    } catch (JizzaException e) {
      return reply("I couldn't run \"" + workflow.name() + "\": " + e.getMessage());
    }
    String status = run.status().name().toLowerCase(Locale.ROOT);
    StringBuilder said = new StringBuilder("Started **").append(workflow.name()).append("**");
    said.append(switch (run.status()) {
      case DONE -> ". It has already finished.";
      case FAILED -> ", but it failed straight away. The run log says why.";
      case CANCELLED -> ", but it was cancelled.";
      case PAUSED_WAITING_INSTRUCTION -> ". It is waiting for your answer in Notifications.";
      case WAITING -> ". It is waiting for its next step's time and carries on by itself.";
      case RUNNING -> ". It is running now; Notifications will tell you when it needs you.";
    });
    if (newestOfSeveral) said.append(" You have more than one workflow with this name; I ran the newest.");
    if (!unknown.isEmpty()) {
      said.append(" It does not take ").append(String.join(", ", unknown)).append(", so I left ")
          .append(unknown.size() == 1 ? "that" : "those").append(" out.");
    }
    UiAction ran = new UiAction(UiAction.REUSED_WORKFLOW, Json.obj()
        .put("workflowId", workflow.workflowId().toString()).put("name", workflow.name())
        .put("runId", run.id().toString()).put("status", status));
    return new Orchestrator.Turn(said.toString(), List.of(), List.of(ran), 0, 0);
  }

  /** The {@code name: value} lines after the command. */
  static Map<String, String> inputs(String[] lines) {
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 1; i < lines.length; i++) {
      Matcher m = INPUT.matcher(lines[i]);
      if (m.matches() && !m.group(2).isBlank()) out.put(m.group(1).strip(), m.group(2).strip());
    }
    return out;
  }

  private static Orchestrator.Turn choose(String question, List<WorkflowLibrary.Match> options) {
    ObjectNode ask = Json.obj().put("question", question);
    ArrayNode list = ask.putArray("options");
    options.stream().limit(CHOICES).forEach(w -> list.add(COMMAND + " " + w.name()));
    return new Orchestrator.Turn(question, List.of(), List.of(new UiAction(UiAction.ASK_USER, ask)), 0, 0);
  }

  private static Orchestrator.Turn reply(String text) {
    return new Orchestrator.Turn(text, List.of(), List.of(), 0, 0);
  }

  /** What was typed after the command, without quotes around it. */
  static String clean(String typed) {
    if (typed == null) return "";
    String s = typed.strip();
    if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
      s = s.substring(1, s.length() - 1).strip();
    }
    return s;
  }

  private static String key(String name) {
    return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
  }
}
