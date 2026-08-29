package com.jizza.common.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends JizzaException {
  public ConflictException(String detail) {
    super(HttpStatus.CONFLICT, "conflict", "Conflict", detail);
  }
}
