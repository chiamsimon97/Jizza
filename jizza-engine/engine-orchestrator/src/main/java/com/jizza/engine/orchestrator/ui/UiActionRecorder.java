package com.jizza.engine.orchestrator.ui;

import com.jizza.engine.api.orchestrator.UiAction;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Collects, for one chat turn, the screen actions Jizza asked for. Turn-scoped like
 * {@link com.jizza.engine.orchestrator.delegation.DelegationRecorder}: the orchestrator opens a turn,
 * the app-control tools append, and the orchestrator attaches what was collected to the reply.
 */
@Component
public class UiActionRecorder {

  private static final ThreadLocal<List<UiAction>> TURN = new ThreadLocal<>();
  private static final ThreadLocal<String> STEP = new ThreadLocal<>();

  public void beginTurn() {
    beginTurn(null);
  }

  /** {@code stepId} is the Builder step whose settings are open with Jizza on them, if any. */
  public void beginTurn(String stepId) {
    TURN.set(new ArrayList<>());
    if (stepId == null || stepId.isBlank()) STEP.remove();
    else STEP.set(stepId);
  }

  /** The Builder step Jizza may fill in this turn, or null when none is open. */
  public String stepInFocus() {
    return TURN.get() == null ? null : STEP.get();
  }

  /** False when no turn is open, i.e. the agent is not talking to someone looking at the app. */
  public boolean active() {
    return TURN.get() != null;
  }

  public void record(UiAction action) {
    List<UiAction> turn = TURN.get();
    if (turn != null) turn.add(action);
  }

  public List<UiAction> endTurn() {
    List<UiAction> turn = TURN.get();
    TURN.remove();
    STEP.remove();
    return turn == null ? List.of() : List.copyOf(turn);
  }
}
