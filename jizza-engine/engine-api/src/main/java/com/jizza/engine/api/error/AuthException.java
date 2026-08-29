package com.jizza.engine.api.error;

import org.springframework.http.HttpStatus;

public class AuthException extends JizzaException {
  public AuthException(String detail) {
    super(HttpStatus.UNAUTHORIZED, "authentication", "Authentication failed", detail);
  }
  public AuthException(String type, String detail) {
    super(HttpStatus.UNAUTHORIZED, type, "Authentication failed", detail);
  }
}
