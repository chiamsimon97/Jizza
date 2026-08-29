package com.jizza.common.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationDto(UUID id, Instant createdAt, List<MessageDto> messages) {

  public record MessageDto(UUID id, String role, String content, Instant createdAt) {}
}
