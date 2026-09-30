package com.jizza.engine.api.orchestrator;

import com.jizza.engine.api.dto.ConversationDto;
import java.util.List;
import java.util.UUID;

/** Read-only view over persisted conversations and their messages. */
public interface ConversationStore {

  List<ConversationDto> list(UUID userId, int limit);

  ConversationDto get(UUID userId, UUID conversationId);

  List<ConversationDto.MessageDto> messages(UUID userId, UUID conversationId);
}
