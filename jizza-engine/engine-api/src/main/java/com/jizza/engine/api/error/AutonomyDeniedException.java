package com.jizza.engine.api.error;

import org.springframework.http.HttpStatus;

/** Raised when the autonomy policy or the kill switch blocks an action (R40/R41). */
public class AutonomyDeniedException extends JizzaException {
  public AutonomyDeniedException(String detail) {
    super(HttpStatus.FORBIDDEN, "autonomy-denied", "Blocked by autonomy control", detail);
  }
}
