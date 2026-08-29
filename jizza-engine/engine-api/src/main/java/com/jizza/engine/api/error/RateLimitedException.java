package com.jizza.engine.api.error;

import org.springframework.http.HttpStatus;

public class RateLimitedException extends JizzaException {
  public RateLimitedException(String detail) {
    super(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests", detail);
  }
}
