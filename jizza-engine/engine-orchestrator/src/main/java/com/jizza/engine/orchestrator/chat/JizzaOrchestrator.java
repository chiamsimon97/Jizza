package com.jizza.engine.orchestrator.chat;

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
import com.jizza.engine.orchestrator.ui.UiActionRecorder;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.List;
import java.util.UUID;
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

  public JizzaOrchestrator(AgentRepository agents, Agent baseAgent, DelegationRecorder recorder,
                           UiActionRecorder uiActions) {
    this.agents = agents;
    this.baseAgent = baseAgent;
    this.recorder = recorder;
    this.uiActions = uiActions;
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
    recorder.beginTurn();
    if (input.screen() != null) uiActions.beginTurn();
    AgentResult result;
    List<DelegationRecorder.Contribution> contributions;
    List<UiAction> actions;
    try {
      result = baseAgent.run(new AgentRequest(userId, jizza.getId(),
          withScreen(input.message(), input.screen()), null, input.history(), ModelTier.STANDARD,
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

  /** Tells the model where the user is, for this turn only. */
  private static String withScreen(String message, String screen) {
    if (screen == null || screen.isBlank()) return message;
    if (WORKFLOW_PLANNER.equals(screen)) return message + "\n\n" + PLANNER_GUIDE;
    String page = screen.length() > 40 ? screen.substring(0, 40) : screen;
    return message + "\n\n[App context: the user is on the '" + page + "' page of the Jizza app.]";
  }
}
