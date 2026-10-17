package com.jizza.engine.api.llm;

import com.jizza.engine.api.error.BudgetExceededException;

/**
 * Per-run guard rail for the agent loop (R16). Not thread-safe by design: one budget per run.
 */
public final class RunBudget {

  private final int maxIterations;
  private final int maxTokens;
  private int iterations;
  private int tokens;

  public RunBudget(int maxIterations, int maxTokens) {
    this.maxIterations = maxIterations;
    this.maxTokens = maxTokens;
  }

  public void nextIteration() {
    if (++iterations > maxIterations) {
      throw new BudgetExceededException("Tool-call iteration cap of " + maxIterations + " reached");
    }
  }

  public void addTokens(int input, int output) {
    tokens += input + output;
    if (tokens > maxTokens) {
      throw new BudgetExceededException("Token budget of " + maxTokens + " exhausted for this run");
    }
  }

  public boolean iterationsExhausted() { return iterations >= maxIterations; }
  public int iterations() { return iterations; }
  public int tokens() { return tokens; }
}
