package com.jizza.engine.api.error;

import org.springframework.http.HttpStatus;

public class ValidationException extends JizzaException {
  public ValidationException(String detail) {
    super(HttpStatus.BAD_REQUEST, "validation", "Invalid request", detail);
  }
}
