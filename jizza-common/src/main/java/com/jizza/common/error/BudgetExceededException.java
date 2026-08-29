package com.jizza.common.error;

import org.springframework.http.HttpStatus;

/** Raised when a run exhausts its token/cost or iteration budget (R16/R49). */
public class BudgetExceededException extends JizzaException {
  public BudgetExceededException(String detail) {
    super(HttpStatus.TOO_MANY_REQUESTS, "budget-exceeded", "Budget exceeded", detail);
  }
}
