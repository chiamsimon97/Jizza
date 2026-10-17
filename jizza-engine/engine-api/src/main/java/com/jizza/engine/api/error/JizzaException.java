package com.jizza.engine.api.error;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Base for every application error. Carries enough information to render an RFC 9457
 * {@code application/problem+json} body without any module inventing its own error shape.
 */
public class JizzaException extends RuntimeException {

  public static final String TYPE_BASE = "https://jizza.local/problems/";

  private final HttpStatus status;
  private final String type;
  private final String title;

  public JizzaException(HttpStatus status, String type, String title, String detail) {
    this(status, type, title, detail, null);
  }

  public JizzaException(HttpStatus status, String type, String title, String detail, Throwable cause) {
    super(detail, cause);
    this.status = status;
    this.type = type;
    this.title = title;
  }

  public HttpStatus status() { return status; }
  public String type() { return type; }
  public String title() { return title; }

  public ProblemDetail toProblemDetail() {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, getMessage());
    pd.setType(URI.create(TYPE_BASE + type));
    pd.setTitle(title);
    return pd;
  }
}
