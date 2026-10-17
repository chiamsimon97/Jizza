package com.jizza.engine.api.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Marks the thread while an agent is being tried out, so anything it hands work to is tried out
 * too and nothing anywhere in the team changes the outside world. What the trial would have done
 * is collected for whoever started it.
 */
public final class TrialRun {

  /** A call the trial did not carry out, and what the guardrails would have done with it. */
  public record Held(UUID agentId, String agentName, String tool, String wouldDo, String reason) {}

  private static final ThreadLocal<List<Held>> ACTIVE = new ThreadLocal<>();

  private TrialRun() {}

  public static boolean active() {
    return ACTIVE.get() != null;
  }

  /** Runs {@code work} as a trial when {@code trial} is set; nested trials share one list. */
  public static <T> T run(boolean trial, Supplier<T> work) {
    return run(trial, ACTIVE.get() != null ? ACTIVE.get()
        : Collections.synchronizedList(new ArrayList<>()), work);
  }

  /** Runs on another thread as part of the same trial, for hand-offs made in parallel. */
  public static <T> T run(boolean trial, List<Held> held, Supplier<T> work) {
    if (!trial) return work.get();
    List<Held> previous = ACTIVE.get();
    ACTIVE.set(held);
    try {
      return work.get();
    } finally {
      if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
    }
  }

  /** The current trial's list, to carry onto another thread; null outside a trial. */
  public static List<Held> current() {
    return ACTIVE.get();
  }

  public static void hold(Held held) {
    List<Held> list = ACTIVE.get();
    if (list != null) list.add(held);
  }

  /** Runs a trial and returns what it produced together with everything it held back. */
  public static <T> Result<T> record(Supplier<T> work) {
    List<Held> held = Collections.synchronizedList(new ArrayList<>());
    T value = run(true, held, work);
    synchronized (held) {
      return new Result<>(value, List.copyOf(held));
    }
  }

  public record Result<T>(T value, List<Held> held) {}
}
