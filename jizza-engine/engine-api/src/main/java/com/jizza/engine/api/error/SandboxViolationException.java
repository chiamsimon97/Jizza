package com.jizza.engine.api.error;

import org.springframework.http.HttpStatus;

/** Raised when a Transform node tries to escape the JEXL sandbox (R9). */
public class SandboxViolationException extends JizzaException {
  public SandboxViolationException(String detail) {
    super(HttpStatus.BAD_REQUEST, "sandbox-violation", "Sandbox violation", detail);
  }
}
