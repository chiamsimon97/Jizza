package com.jizza.engine.api.llm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.BudgetExceededException;
import org.junit.jupiter.api.Test;

class RunBudgetTest {

  @Test
  void capsToolCallIterations() {
    RunBudget budget = new RunBudget(2, 1_000);
    budget.nextIteration();
    budget.nextIteration();
    assertThatThrownBy(budget::nextIteration).isInstanceOf(BudgetExceededException.class)
        .hasMessageContaining("iteration cap of 2");
  }

  @Test
  void capsTokenSpend() {
    RunBudget budget = new RunBudget(10, 100);
    assertThatCode(() -> budget.addTokens(40, 40)).doesNotThrowAnyException();
    assertThatThrownBy(() -> budget.addTokens(20, 10)).isInstanceOf(BudgetExceededException.class);
  }
}
