package com.jizza.engine.api.llm;

import java.util.UUID;

/** Token/cost accounting and per-run budgets (R49). */
public interface CostAccountant {

  void record(UUID userId, String model, int inputTokens, int outputTokens, String purpose);

  /**
   * The same, with the cached part of the input named so it is priced at the cache rates.
   * {@code inputTokens} still counts every input token. An accountant that does not price the cache
   * charges all of it at the full rate, which errs towards the budget stopping early.
   */
  default void record(UUID userId, String model, int inputTokens, int outputTokens,
                      int cacheReadTokens, int cacheWriteTokens, String purpose) {
    record(userId, model, inputTokens, outputTokens, purpose);
  }

  /** Records one model call, falling back to the requested model when the reply names none. */
  default void record(UUID userId, String requestedModel, LlmResponse response, String purpose) {
    // A model on the person's own key is recorded as asked for, so it is priced from their catalog.
    record(userId, response.model() == null || ModelRefs.ownKey(requestedModel) ? requestedModel
            : response.model(),
        response.inputTokens(), response.outputTokens(), response.cacheReadTokens(),
        response.cacheWriteTokens(), purpose);
  }

  /**
   * What one call cost, in the same unit as {@link #spendInWindow}, without recording it. Zero when
   * the model's price is not known.
   */
  default double costOf(UUID userId, String model, LlmResponse response) {
    return 0d;
  }

  /** @return true when the user is still inside their configured budget. */
  boolean withinBudget(UUID userId);

  /** Spend so far in the current accounting window, in the configured currency unit. */
  double spendInWindow(UUID userId);

  /** Spend recorded under one purpose (such as {@code agent:<key>}) since a moment. */
  default double spendSince(UUID userId, String purpose, java.time.Instant since) {
    return 0d;
  }
}
