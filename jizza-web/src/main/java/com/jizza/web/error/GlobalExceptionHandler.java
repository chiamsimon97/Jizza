package com.jizza.web.error;

import com.jizza.common.error.JizzaException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One RFC 9457 {@code application/problem+json} shape for every error the API returns.
 *
 * <p>Deliberate: known errors carry their own detail, but an unexpected one does not. An internal
 * message can contain a query, a header or a fragment of a token, so it goes to the log with the
 * request id and the client gets that id to quote instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(JizzaException.class)
  public ProblemDetail handleJizza(JizzaException e, HttpServletRequest request) {
    ProblemDetail problem = e.toProblemDetail();
    problem.setInstance(java.net.URI.create(request.getRequestURI()));
    problem.setProperty("requestId", org.slf4j.MDC.get("requestId"));
    if (problem.getStatus() >= 500) {
      log.error("Request {} failed", request.getRequestURI(), e);
    }
    return problem;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException e,
                                        HttpServletRequest request) {
    String detail = e.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getField() + " " + error.getDefaultMessage())
        .reduce((a, b) -> a + "; " + b)
        .orElse("The request was not valid");

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    problem.setType(java.net.URI.create(JizzaException.TYPE_BASE + "validation"));
    problem.setTitle("Invalid request");
    problem.setInstance(java.net.URI.create(request.getRequestURI()));
    return problem;
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  public ProblemDetail handleAccessDenied(org.springframework.security.access.AccessDeniedException e,
                                          HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
        "You do not have access to this");
    problem.setType(java.net.URI.create(JizzaException.TYPE_BASE + "forbidden"));
    problem.setTitle("Forbidden");
    problem.setInstance(java.net.URI.create(request.getRequestURI()));
    return problem;
  }

  /**
   * A path nobody serves is a client mistake, not a server failure. Without this it falls through
   * to the catch-all below and a mistyped URL is reported -- and logged at ERROR -- as if the
   * service had broken.
   */
  @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
      org.springframework.web.servlet.NoHandlerFoundException.class})
  public ProblemDetail handleNoRoute(Exception e, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
        "There is nothing at " + request.getRequestURI());
    problem.setType(java.net.URI.create(JizzaException.TYPE_BASE + "not-found"));
    problem.setTitle("Not found");
    problem.setInstance(java.net.URI.create(request.getRequestURI()));
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
    String requestId = org.slf4j.MDC.get("requestId");
    log.error("Unhandled error on {} (request {})", request.getRequestURI(), requestId, e);

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
        "Something went wrong. Quote request id " + requestId + " if you report this.");
    problem.setType(java.net.URI.create(JizzaException.TYPE_BASE + "internal"));
    problem.setTitle("Internal error");
    problem.setInstance(java.net.URI.create(request.getRequestURI()));
    problem.setProperty("requestId", requestId);
    return problem;
  }
}
