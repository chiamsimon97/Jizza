package com.jizza.engine.api.error;

import org.springframework.http.HttpStatus;

/** Raised when a sensitive route is hit without a fresh step-up re-authentication (R4). */
public class StepUpRequiredException extends JizzaException {
  public StepUpRequiredException() {
    super(HttpStatus.FORBIDDEN, "step-up-required", "Step-up re-authentication required",
        "Confirm your password to continue (POST /api/auth/step-up).");
  }
}
