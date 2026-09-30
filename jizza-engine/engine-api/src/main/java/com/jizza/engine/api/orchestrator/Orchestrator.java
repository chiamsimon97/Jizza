package com.jizza.engine.api.orchestrator;

import java.util.UUID;

/** Jizza. The only entry point for user messages (R18). */
public interface Orchestrator {

  AssistantReply handle(UUID userId, String message);

  AssistantReply handle(UUID userId, UUID conversationId, String message);

  /** Incognito turn: messages are not persisted. conversationId is always null on the reply. */
  AssistantReply handleIncognito(UUID userId, String message);
}
