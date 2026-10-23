package com.jizza.platform.api.error;

import com.jizza.engine.api.error.JizzaException;
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

  /** An upload over the multipart limit is the user's file being too big, not a server fault. */
  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ProblemDetail handleTooLarge(Exception e, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
        "That file is too large. Files are limited to 10 MB.");
    problem.setType(java.net.URI.create(JizzaException.TYPE_BASE + "validation"));
    problem.setTitle("File too large");
    problem.setInstance(java.net.URI.create(request.getRequestURI()));
    return problem;
  }

  /**
   * A request the client got wrong before any of our code ran: a body that is not JSON, an id
   * that is not a UUID, a missing parameter or file part, a method or content type the route does
   * not take. These are the caller's mistake and are answered as such, not as a server failure.
   */
  @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
      org.springframework.beans.TypeMismatchException.class,
      org.springframework.web.multipart.MultipartException.class,
      org.springframework.web.bind.ServletRequestBindingException.class,
      org.springframework.web.multipart.support.MissingServletRequestPartException.class,
      org.springframework.web.HttpMediaTypeException.class,
      org.springframework.web.HttpRequestMethodNotSupportedException.class,
      org.springframework.web.method.annotation.HandlerMethodValidationException.class,
      jakarta.validation.ConstraintViolationException.class})
  public ProblemDetail handleBadRequest(Exception e, HttpServletRequest request) {
    HttpStatus status = HttpStatus.BAD_REQUEST;
    String detail = "The request was not valid";
    if (e instanceof org.springframework.web.ErrorResponse response
        && response.getStatusCode().is4xxClientError()) {
      status = HttpStatus.valueOf(response.getStatusCode().value());
    }
    if (e instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
      detail = "The request body is missing or is not valid JSON";
    } else if (e instanceof org.springframework.beans.TypeMismatchException mismatch) {
      String name = mismatch instanceof org.springframework.web.method.annotation
          .MethodArgumentTypeMismatchException arg ? arg.getName() : mismatch.getPropertyName();
      detail = (name == null ? "A value" : "'" + name + "'") + " is not in the expected format";
    } else if (e instanceof org.springframework.web.bind.MissingServletRequestParameterException
        missing) {
      detail = "'" + missing.getParameterName() + "' is required";
    } else if (e instanceof org.springframework.web.multipart.support
        .MissingServletRequestPartException missing) {
      detail = "'" + missing.getRequestPartName() + "' is required";
    } else if (e instanceof org.springframework.web.multipart.MultipartException) {
      detail = "Send this as a multipart file upload";
    } else if (e instanceof org.springframework.web.HttpMediaTypeNotSupportedException) {
      detail = "That content type is not accepted here";
    } else if (e instanceof org.springframework.web.HttpRequestMethodNotSupportedException method) {
      detail = method.getMethod() + " is not supported here";
    } else if (e instanceof jakarta.validation.ConstraintViolationException violation) {
      detail = violation.getConstraintViolations().stream()
          .map(v -> v.getPropertyPath() + " " + v.getMessage())
          .reduce((a, b) -> a + "; " + b).orElse(detail);
    }
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(java.net.URI.create(JizzaException.TYPE_BASE + "validation"));
    problem.setTitle("Invalid request");
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
