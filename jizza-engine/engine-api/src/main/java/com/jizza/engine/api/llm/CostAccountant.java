package com.jizza.engine.api.llm;

import java.util.UUID;

/** Token/cost accounting and per-run budgets (R49). */
public interface CostAccountant {

  void record(UUID userId, String model, int inputTokens, int outputTokens, String purpose);

  /** @return true when the user is still inside their configured budget. */
  boolean withinBudget(UUID userId);

  /** Spend so far in the current accounting window, in the configured currency unit. */
  double spendInWindow(UUID userId);

  /** Spend recorded under one purpose (such as {@code agent:<key>}) since a moment. */
  default double spendSince(UUID userId, String purpose, java.time.Instant since) {
    return 0d;
  }
}
