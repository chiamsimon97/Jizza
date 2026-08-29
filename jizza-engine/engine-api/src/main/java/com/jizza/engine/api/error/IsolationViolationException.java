package com.jizza.engine.api.error;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Raised when an agent attempts to touch another agent's knowledge namespace (R22). */
public class IsolationViolationException extends JizzaException {
  public IsolationViolationException(UUID callingAgent, UUID targetNamespace) {
    super(HttpStatus.FORBIDDEN, "knowledge-isolation",
        "Knowledge namespace isolation violation",
        "Agent " + callingAgent + " may not access namespace " + targetNamespace);
  }
}
