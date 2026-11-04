package com.jizza.engine.api.error;

import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Plain messages for failures that are ours, not the person's. People using Jizza are not
 * developers, so a provider's raw reply or a stack trace's message means nothing to them: they get
 * a short message and a reference, and the full details go to the server log under the same
 * reference. Errors about what the person did (4xx: a bad value, a budget reached, a sign-in
 * needed) already speak to them and are passed on unchanged.
 */
public final class FriendlyErrors {

  private FriendlyErrors() {}

  /** Problem types of failures talking to the AI model provider. */
  public static final Set<String> MODEL_TYPES = Set.of("llm-upstream", "llm-unreachable");

  /** Whether the error is internal: anything that is not a JizzaException below 500. */
  public static boolean isInternal(Throwable e) {
    return !(e instanceof JizzaException je) || je.status().is5xxServerError();
  }

  /** A short reference to quote, shown to the person and written to the log. */
  public static String newReference() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  /**
   * The error as the person should see it: unchanged when it is about what they did, otherwise
   * the same status and problem type with a plain title and message carrying {@code reference}.
   */
  public static JizzaException forPerson(Throwable e, String reference) {
    if (!isInternal(e)) return (JizzaException) e;
    HttpStatus status = e instanceof JizzaException je ? je.status() : HttpStatus.INTERNAL_SERVER_ERROR;
    String type = e instanceof JizzaException je ? je.type() : "internal";
    String suffix = reference == null || reference.isBlank() ? ""
        : " If it keeps happening, share reference " + reference + " with support.";
    if (MODEL_TYPES.contains(type)) {
      return new JizzaException(status, type, "Jizza couldn't answer",
          "Jizza couldn't get an answer from its AI service just now. Please try again in a moment."
              + suffix, e);
    }
    return new JizzaException(status, type, "Something went wrong",
        "Something went wrong on our side. Please try again." + suffix, e);
  }
}
