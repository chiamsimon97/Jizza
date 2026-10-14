package com.jizza.engine.api.memory;

import java.util.UUID;

/**
 * Promotes durable facts out of a finished conversation turn into long-term memory.
 *
 * <p>Selective by design: most of a conversation is not worth keeping, so only facts that will
 * still be true and useful later are promoted, and only when the user has auto-learn switched on.
 * Implementations run off the request thread; a failure never affects the reply.
 */
public interface MemoryLearner {

  void afterTurn(Turn turn);

  record Turn(UUID userId, UUID conversationId, UUID userMessageId, String userText,
              String assistantText) {}
}
