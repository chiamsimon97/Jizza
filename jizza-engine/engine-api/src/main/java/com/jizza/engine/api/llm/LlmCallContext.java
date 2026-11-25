package com.jizza.engine.api.llm;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Who a model call is made for, and how hard it should think, on the thread making it. An agent
 * run sets it around its loop, so a model on the person's own key is reached with that person's key
 * and nobody else's, whichever thread the run is on. Outside a run (the router, background upkeep)
 * nothing is set and only platform models are used.
 */
public final class LlmCallContext {

  /** @param effort low, medium or high to override the tier's own setting; null keeps it */
  public record Call(UUID userId, String effort) {}

  private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();

  private LlmCallContext() {}

  public static Optional<Call> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static Optional<UUID> userId() {
    return current().map(Call::userId);
  }

  public static Optional<String> effort() {
    return current().map(Call::effort).filter(e -> !e.isBlank());
  }

  public static <T> T run(UUID userId, String effort, Supplier<T> work) {
    Call previous = CURRENT.get();
    CURRENT.set(new Call(userId, effort));
    try {
      return work.get();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }
}
