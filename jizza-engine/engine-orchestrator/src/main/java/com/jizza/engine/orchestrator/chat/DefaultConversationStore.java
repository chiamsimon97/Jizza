package com.jizza.engine.orchestrator.chat;

import com.jizza.engine.api.dto.ConversationDto;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.orchestrator.ConversationStore;
import com.jizza.engine.persistence.entity.ConversationEntity;
import com.jizza.engine.persistence.entity.MessageEntity;
import com.jizza.engine.persistence.repository.ConversationRepository;
import com.jizza.engine.persistence.repository.MessageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
class DefaultConversationStore implements ConversationStore {

  private final ConversationRepository conversations;
  private final MessageRepository messages;

  DefaultConversationStore(ConversationRepository conversations, MessageRepository messages) {
    this.conversations = conversations;
    this.messages = messages;
  }

  @Override
  public List<ConversationDto> list(UUID userId, int limit) {
    return conversations.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit))
        .stream()
        .map(c -> new ConversationDto(c.getId(), c.getCreatedAt(), List.of()))
        .toList();
  }

  @Override
  public ConversationDto get(UUID userId, UUID conversationId) {
    ConversationEntity entity = conversations.findById(conversationId)
        .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
    if (!entity.getUserId().equals(userId)) {
      throw new NotFoundException("Conversation", conversationId);
    }
    List<ConversationDto.MessageDto> msgs = toMessageDtos(
        messages.findByConversationIdOrderByCreatedAtAsc(conversationId));
    return new ConversationDto(entity.getId(), entity.getCreatedAt(), msgs);
  }

  @Override
  public List<ConversationDto.MessageDto> messages(UUID userId, UUID conversationId) {
    ConversationEntity entity = conversations.findById(conversationId)
        .orElseThrow(() -> new NotFoundException("Conversation", conversationId));
    if (!entity.getUserId().equals(userId)) {
      throw new NotFoundException("Conversation", conversationId);
    }
    return toMessageDtos(messages.findByConversationIdOrderByCreatedAtAsc(conversationId));
  }

  private List<ConversationDto.MessageDto> toMessageDtos(List<MessageEntity> entities) {
    return entities.stream()
        .map(m -> new ConversationDto.MessageDto(
            m.getId(), m.getRole().name().toLowerCase(), m.getContent(), m.getCreatedAt()))
        .toList();
  }
}
