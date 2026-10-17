package com.jizza.engine.api.enums;

import java.util.Locale;

/**
 * What a tool does to the world, which is what a guardrail is written against. Ordered from the
 * least to the most consequential, so a classification can only ever be raised, never lowered,
 * by comparing ordinals.
 */
public enum ActionType {
  /** Only reads, or only speaks to the user through Jizza. */
  READ,
  /** Changes only Jizza's own records (memory, plans, notes), never anything outside it. */
  DRAFT,
  CREATE,
  UPDATE,
  DELETE,
  /** Sends a message to a person: an email, a chat, a post. */
  SEND,
  /** Moves money or places a trade. */
  MONEY;

  /** True for anything that changes something outside Jizza. */
  public boolean sideEffecting() {
    return this != READ && this != DRAFT;
  }

  /** The type an HTTP method implies before anyone looks closer. */
  public static ActionType fromHttpMethod(String method) {
    if (method == null) return UPDATE;
    return switch (method.toUpperCase(Locale.ROOT)) {
      case "GET", "HEAD", "OPTIONS" -> READ;
      case "POST" -> CREATE;
      case "PUT", "PATCH" -> UPDATE;
      case "DELETE" -> DELETE;
      default -> UPDATE;
    };
  }

  /** The more consequential of the two. */
  public static ActionType riskier(ActionType a, ActionType b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.ordinal() >= b.ordinal() ? a : b;
  }

  public String token() {
    return "*:" + name().toLowerCase(Locale.ROOT);
  }
}
