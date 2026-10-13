package com.jizza.engine.api.orchestrator;

import java.util.UUID;

/** Jizza. The only entry point for user messages (R18). */
public interface Orchestrator {

  AssistantReply handle(UUID userId, String message);

  AssistantReply handle(UUID userId, UUID conversationId, String message);

  /**
   * A turn sent from inside the app. {@code screen} names the page the user is looking at so Jizza
   * can act on it; it is context for this turn only and is not stored with the message.
   */
  default AssistantReply handle(UUID userId, UUID conversationId, String message, String screen) {
    return handle(userId, conversationId, message);
  }

  /** Incognito turn: messages are not persisted. conversationId is always null on the reply. */
  AssistantReply handleIncognito(UUID userId, String message);

  default AssistantReply handleIncognito(UUID userId, String message, String screen) {
    return handleIncognito(userId, message);
  }
}
