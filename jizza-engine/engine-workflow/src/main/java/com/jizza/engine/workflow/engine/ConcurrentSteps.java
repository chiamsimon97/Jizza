package com.jizza.engine.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivitySink;
import com.jizza.engine.api.agent.TeamTrail;
import com.jizza.engine.api.agent.TrialRun;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.cosmos.AccessContext;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The steps of one run that are executing on other threads while the run's own worker carries on.
 *
 * <p>Only the step's executor runs on the other thread. Everything that reads or writes the run's
 * state (the context, checkpoints, the run row) stays on the run's worker, which applies each
 * result as it comes in, so the bookkeeping has one writer and one transaction exactly as before.
 * What the job's thread carries (activity, access context, trial, chat scope, team trail) is
 * captured when the run's worker builds this and re-entered on each step's thread.
 */
final class ConcurrentSteps {

  /** One step's finished attempt, to be applied on the run's worker. */
  record Result(String nodeId, int attempt, JsonNode input, Instant started,
                NodeExecutor.Outcome outcome) {}

  private final CompletionService<Result> done;
  private final Set<String> inFlight = new HashSet<>();
  private final ActivitySink activity = Activity.current().orElse(null);
  private final AccessContext access = AccessContext.current().orElse(null);
  private final boolean trial = TrialRun.active();
  private final List<TrialRun.Held> held = TrialRun.current();
  private final ChatScope.Scope chat = ChatScope.current().orElse(null);
  private final TeamTrail trail = TeamTrail.current();

  ConcurrentSteps(ExecutorService executor) {
    this.done = new ExecutorCompletionService<>(executor);
  }

  boolean running(String nodeId) { return inFlight.contains(nodeId); }

  int size() { return inFlight.size(); }

  boolean isEmpty() { return inFlight.isEmpty(); }

  /**
   * Starts one attempt of a step, after {@code delayMillis} when it is a retry backing off. The
   * delay is spent on the step's own thread, so a path backing off holds up no other path.
   */
  void submit(String nodeId, int attempt, JsonNode input, long delayMillis,
              Supplier<NodeExecutor.Outcome> work) {
    inFlight.add(nodeId);
    done.submit(() -> {
      if (delayMillis > 0) Thread.sleep(delayMillis);
      Instant started = Instant.now();
      NodeExecutor.Outcome outcome;
      try {
        outcome = carried(work);
      } catch (RuntimeException e) {
        outcome = NodeExecutor.Outcome.retryable(String.valueOf(e.getMessage()));
      }
      return new Result(nodeId, attempt, input, started, outcome);
    });
  }

  /** The next finished step, waiting up to {@code millis}; null if none finished in time. */
  Result poll(long millis) throws InterruptedException {
    Future<Result> next = millis <= 0 ? done.poll() : done.poll(millis, TimeUnit.MILLISECONDS);
    if (next == null) return null;
    Result result;
    try {
      result = next.get();
    } catch (ExecutionException e) {
      // The task itself catches what the step throws, so this is only an interrupted back-off.
      throw new IllegalStateException("A step running alongside others did not finish", e.getCause());
    }
    inFlight.remove(result.nodeId());
    return result;
  }

  private NodeExecutor.Outcome carried(Supplier<NodeExecutor.Outcome> work) {
    Supplier<NodeExecutor.Outcome> inTrail = () -> TeamTrail.runWith(trail, work);
    Supplier<NodeExecutor.Outcome> inChat = chat == null ? inTrail : () -> ChatScope.run(chat, inTrail);
    Supplier<NodeExecutor.Outcome> inTrial = () -> TrialRun.run(trial, held, inChat);
    Supplier<NodeExecutor.Outcome> inAccess = access == null ? inTrial : () -> access.run(inTrial);
    return Activity.run(activity, inAccess);
  }
}
