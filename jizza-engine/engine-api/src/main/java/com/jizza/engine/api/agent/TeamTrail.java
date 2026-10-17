package com.jizza.engine.api.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Who handed work to whom while one piece of work ran: a workflow step, a trial of an agent, a
 * chat turn. Whoever wants the trail opens one around the run; delegation appends to it. Hand-offs
 * that run in parallel append from their own threads through {@link #runWith}.
 */
public final class TeamTrail {

  /** One hand-off. {@code depth} is 1 for the first agent's direct reports. */
  public record Step(UUID fromAgentId, String fromName, UUID toAgentId, String toName, int depth,
                     String task, String answer, boolean failed, int tokens) {}

  private static final ThreadLocal<TeamTrail> CURRENT = new ThreadLocal<>();

  private final List<Step> steps = Collections.synchronizedList(new ArrayList<>());

  private TeamTrail() {}

  /** Runs {@code work} with a fresh trail and returns both. */
  public static <T> Recorded<T> record(Supplier<T> work) {
    TeamTrail previous = CURRENT.get();
    TeamTrail trail = new TeamTrail();
    CURRENT.set(trail);
    try {
      return new Recorded<>(work.get(), trail.steps());
    } finally {
      if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
  }

  public record Recorded<T>(T value, List<Step> steps) {}

  public static TeamTrail current() {
    return CURRENT.get();
  }

  /** Runs on another thread with the given trail current, for hand-offs made in parallel. */
  public static <T> T runWith(TeamTrail trail, Supplier<T> work) {
    if (trail == null) return work.get();
    TeamTrail previous = CURRENT.get();
    CURRENT.set(trail);
    try {
      return work.get();
    } finally {
      if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
  }

  public static void add(Step step) {
    TeamTrail trail = CURRENT.get();
    if (trail != null) trail.steps.add(step);
  }

  public List<Step> steps() {
    synchronized (steps) {
      return List.copyOf(steps);
    }
  }
}
