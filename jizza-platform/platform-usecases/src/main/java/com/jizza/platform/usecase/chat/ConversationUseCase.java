package com.jizza.platform.usecase.chat;

import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.api.orchestrator.ConversationStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Exposes the user's conversation history. Ownership enforcement lives here so the engine port
 * stays mechanism-only (it already guards ownership inside the store, but the use-case is the
 * right layer to apply any cross-cutting validation or limits).
 */
@Service
public class ConversationUseCase {

  private static final int DEFAULT_LIMIT = 50;

  private final ConversationStore store;

  public ConversationUseCase(ConversationStore store) {
    this.store = store;
  }

  public List<ConversationDto> list(UUID userId, int limit) {
    return store.list(userId, Math.min(limit, DEFAULT_LIMIT));
  }

  public ConversationDto get(UUID userId, UUID conversationId) {
    return store.get(userId, conversationId);
  }

  public List<ConversationDto.MessageDto> messages(UUID userId, UUID conversationId) {
    return store.messages(userId, conversationId);
  }
}
