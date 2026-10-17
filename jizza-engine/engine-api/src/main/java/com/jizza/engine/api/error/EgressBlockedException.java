package com.jizza.engine.api.error;

import org.springframework.http.HttpStatus;

/** Raised by the SSRF/egress guard in the tool executor (R8). */
public class EgressBlockedException extends JizzaException {
  public EgressBlockedException(String detail) {
    super(HttpStatus.FORBIDDEN, "egress-blocked", "Outbound request blocked", detail);
  }
}
