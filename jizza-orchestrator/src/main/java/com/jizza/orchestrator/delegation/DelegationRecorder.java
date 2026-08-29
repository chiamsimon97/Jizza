package com.jizza.orchestrator.delegation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Collects, for the duration of one chat turn, which specialists Jizza actually consulted. The
 * orchestrator opens a turn before running and closes it afterwards; the delegation tool appends.
 *
 * <p>Turn-scoped rather than request-scoped so it also works on the worker, where a turn may be
 * driven by the scheduler rather than by an HTTP request.
 */
@Component
public class DelegationRecorder {

  private static final ThreadLocal<List<Contribution>> TURN = new ThreadLocal<>();

  public record Contribution(UUID agentId, String agentName, String task, String result) {}

  public void beginTurn() {
    TURN.set(new ArrayList<>());
  }

  public void record(Contribution contribution) {
    List<Contribution> turn = TURN.get();
    if (turn != null) turn.add(contribution);
  }

  public List<Contribution> current() {
    List<Contribution> turn = TURN.get();
    return turn == null ? List.of() : List.copyOf(turn);
  }

  public List<Contribution> endTurn() {
    List<Contribution> turn = current();
    TURN.remove();
    return turn;
  }
}
