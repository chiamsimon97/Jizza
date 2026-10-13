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

  public void beginTurn() {
    TURN.set(new ArrayList<>());
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
    return turn == null ? List.of() : List.copyOf(turn);
  }
}
