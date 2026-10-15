package com.jizza.engine.api.activity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The job the current thread is working for, so code deep in a run (the agent loop, the tool
 * registry, a connector call) can report what it is doing without every signature carrying a sink.
 *
 * <p>Held on the thread like {@code AccessContext}. Work fanned out to other threads (parallel
 * delegation) must capture {@link #current()} and re-enter it with {@link #run}. With no job
 * current, every call here is a no-op: scheduled and workflow runs that nobody is watching pay
 * nothing for it.
 */
public final class Activity {

  private Activity() {}

  private static final ThreadLocal<ActivitySink> CURRENT = new ThreadLocal<>();

  public static Optional<ActivitySink> current() { return Optional.ofNullable(CURRENT.get()); }

  public static boolean active() { return CURRENT.get() != null; }

  /** Runs {@code work} with {@code sink} current, restoring whatever was there. A null sink runs it plainly. */
  public static <T> T run(ActivitySink sink, Supplier<T> work) {
    if (sink == null) return work.get();
    ActivitySink previous = CURRENT.get();
    CURRENT.set(sink);
    try {
      return work.get();
    } finally {
      if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
  }

  public static void emit(String type, UUID agentId, String agentName, Consumer<ObjectNode> payload) {
    ActivitySink sink = CURRENT.get();
    if (sink == null) return;
    ObjectNode node = Json.obj();
    if (payload != null) payload.accept(node);
    try {
      sink.emit(new ActivityEvent(type, agentId, agentName, node));
    } catch (RuntimeException ignored) {
      // Reporting is a window onto the work, never a reason for the work to fail.
    }
  }

  /** Stops the run here if the user pressed Stop. */
  public static void checkCancelled() {
    ActivitySink sink = CURRENT.get();
    if (sink != null && sink.cancelled()) throw new JobCancelledException();
  }
}
