package com.jizza.engine.api.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends JizzaException {
  public NotFoundException(String what, Object id) {
    super(HttpStatus.NOT_FOUND, "not-found", "Not found", what + " " + id + " was not found");
  }
}
