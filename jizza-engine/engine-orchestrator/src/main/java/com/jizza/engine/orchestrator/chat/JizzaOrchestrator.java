package com.jizza.engine.orchestrator.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.cosmos.AccessContext;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.orchestrator.delegation.DelegationRecorder;
import com.jizza.engine.orchestrator.library.LibraryHints;
import com.jizza.engine.orchestrator.ui.UiActionRecorder;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Jizza. The only agent a user message reaches (R18), and the only thing that produces a reply the
 * user sees.
 *
 * <p>Specialists are reachable exclusively through the delegation tool, so there is no code path by
 * which a specialist's output reaches the user unrelayed. What they contributed comes back on the
 * {@link Orchestrator.Turn} for the chat module to attribute and record.
 *
 * <p>Deliberately not transactional. A turn spans model calls and, on a cross-domain request, work
 * fanned out across threads; holding a transaction open across that would pin a connection for
 * the whole turn and let the fan-out wait on locks its own caller is holding.
 */
@Service
public class JizzaOrchestrator implements Orchestrator {

  static final String NO_ANSWER = "I could not put together an answer for that.";

  private final AgentRepository agents;
  private final Agent baseAgent;
  private final DelegationRecorder recorder;
  private final UiActionRecorder uiActions;
  private final TurnTierSelector tiers;
  private LibraryHints libraryHints;

  public JizzaOrchestrator(AgentRepository agents, Agent baseAgent, DelegationRecorder recorder,
                           UiActionRecorder uiActions, TurnTierSelector tiers) {
    this.agents = agents;
    this.baseAgent = baseAgent;
    this.recorder = recorder;
    this.uiActions = uiActions;
    this.tiers = tiers;
  }

  /** Optional: without it Jizza still has find_saved_workflows, just no head start. */
  @Autowired(required = false)
  void setLibraryHints(LibraryHints libraryHints) {
    this.libraryHints = libraryHints;
  }

  /**
   * {@code screen} is set when the message comes from inside the app. Only then does Jizza get a
   * screen to act on: the app-control tools record actions for the reply, and the page the user is
   * on is passed to the model for this turn without being stored with the message.
   */
  @Override
  public Turn respond(UUID userId, TurnInput input) {
    AgentEntity jizza = agents.findByUserIdAndIsOrchestratorTrue(userId)
        .orElseThrow(() -> new NotFoundException("Orchestrator agent for user", userId));

    String trace = input.userMessageId() == null ? null
        : AccessContext.chatTurn(input.userMessageId()).toTrace();
    ModelTier tier = tiers.tierFor(userId, jizza, input.message(), input.screen(), input.history());
    recorder.beginTurn();
    if (input.screen() != null) uiActions.beginTurn(stepInFocus(input.screen(), input.context()));
    AgentResult result;
    List<DelegationRecorder.Contribution> contributions;
    List<UiAction> actions;
    try {
      String message = libraryHints == null ? input.message()
          : libraryHints.annotate(userId, input.message());
      result = baseAgent.run(new AgentRequest(userId, jizza.getId(),
          withScreen(message, input.screen(), input.context()), null, input.history(), tier,
          false, trace));
    } finally {
      contributions = recorder.endTurn();
      actions = uiActions.endTurn();
    }

    for (UiAction action : actions) {
      Activity.emit(ActivityTypes.UI_ACTION, jizza.getId(), jizza.getName(),
          p -> p.put("type", action.type()).set("payload", action.payload()));
    }

    String text = result.text() == null || result.text().isBlank() ? NO_ANSWER : result.text();
    return new Turn(text,
        contributions.stream()
            .map(c -> new Delegation(jizza.getId(), c.agentId(), c.agentName(), c.task(), c.result()))
            .toList(),
        actions, result.inputTokens(), result.outputTokens());
  }

  /** The screen the Workflows page's "Plan with Jizza" panel sends from. */
  static final String WORKFLOW_PLANNER = "workflow_planner";

  /**
   * How a planning conversation runs. The specialists are asked first because they carry the
   * user's memory for their domain; only what they cannot settle goes back to the user.
   */
  static final String PLANNER_GUIDE = """
      [App context: the user is planning a new workflow with you in the workflow planner.
      1. Work out which specialists the request involves and ask them together with
         delegate_to_specialists. Give each the user's request and ask what they already know
         about the user that matters here (accounts, preferences, limits, past results), how they
         would do their part, and what is unclear.
      2. If anything only the user can decide is still open, ask it with app_ask_user: one
         question at a time, with short options, and say briefly what the specialists found.
      3. When nothing is left open, call app_propose_workflow with the plan. Mark each step that
         changes something outside Jizza. List any defaults you picked as assumptions.
         If no one on the user's team fits a step, offer a new agent with app_propose_agent
         first, and propose the workflow once the user has created it.
      Never save the workflow yourself and never use app_open_form here. If the user asks for
      changes after seeing a plan, revise it and propose it again.]""";

  /** The workflow Builder, with the workflow's steps and settings as context. */
  static final String WORKFLOW_BUILDER = "workflow_builder";

  /** One Builder step's settings, opened with "Configure using Jizza". */
  static final String WORKFLOW_STEP = "workflow_step";

  /** How much of what the screen describes is passed on; the client keeps it well under this. */
  static final int MAX_CONTEXT_CHARS = 24_000;

  static final String BUILDER_GUIDE = """
      [App context: the user is in the workflow Builder, working on the workflow described below.
      Answer about this workflow specifically, naming its steps. The workflow runs exactly as its
      steps are configured, with no AI deciding anything at run time unless a step asks an agent;
      say so when it matters. You cannot change the canvas from here: say which step to add or
      change and how, and tell the user they can choose "Configure using Jizza" on a step's menu
      to have you fill that step in. Later steps read earlier results as {{ nodes.<step id>... }}
      and variables as {{ vars.<name> }}.
      Workflow: %s]""";

  static final String STEP_GUIDE = """
      [App context: the user opened "Configure using Jizza" on one step of a workflow in the
      Builder. Your job is to configure that step, and only that step.
      1. Work out its settings from what the user gives you: API documentation, a cURL command,
         an example, or a description. You cannot open links; if all you have is a link, ask the
         user to paste the relevant part (the endpoint, its parameters, how it signs in, an
         example request).
      2. If something only the user can decide is still open, ask with app_ask_user: one short
         question with options. Set multiple=true when several options can apply together, such
         as which optional fields to send. Ask before guessing anything that changes what the
         call does.
      3. Then call app_configure_step with the settings to set. Use only the settings listed under
         "fields"; leave the rest out. Use null to clear one. Where a value comes from an earlier
         step, use a {{ nodes.<step id>... }} reference to it. Never invent a credential, token,
         password or key: leave it out, or keep "(set, hidden)" when one is already set, and tell
         the user to paste theirs into the field.
      4. Say in one or two sentences what you filled in and what is left for the user. Your
         settings appear in the step's panel unsaved; the user reviews them and presses Save.
      The workflow runs the step exactly as configured; you are not part of the run.
      Step and workflow: %s]""";

  /** The step Jizza may fill in this turn: the one whose settings sent the message. */
  static String stepInFocus(String screen, JsonNode context) {
    if (!WORKFLOW_STEP.equals(screen) || context == null) return null;
    String id = context.path("step").path("id").asText("");
    return id.isBlank() ? null : id;
  }

  /** Tells the model where the user is, for this turn only. */
  static String withScreen(String message, String screen, JsonNode context) {
    if (screen == null || screen.isBlank()) return message;
    if (WORKFLOW_PLANNER.equals(screen)) return message + "\n\n" + PLANNER_GUIDE;
    if (stepInFocus(screen, context) != null) {
      return message + "\n\n" + STEP_GUIDE.formatted(described(context));
    }
    if ((WORKFLOW_BUILDER.equals(screen) || WORKFLOW_STEP.equals(screen)) && context != null) {
      return message + "\n\n" + BUILDER_GUIDE.formatted(described(context));
    }
    String page = screen.length() > 40 ? screen.substring(0, 40) : screen;
    return message + "\n\n[App context: the user is on the '" + page + "' page of the Jizza app.]";
  }

  /** The screen's own description, as JSON, cut to a size a turn can afford. */
  static String described(JsonNode context) {
    String json = context.toString();
    return json.length() <= MAX_CONTEXT_CHARS ? json
        : json.substring(0, MAX_CONTEXT_CHARS) + " ...(cut short)";
  }
}
