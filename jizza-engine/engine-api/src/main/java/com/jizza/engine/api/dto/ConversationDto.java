package com.jizza.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationDto(UUID id, Instant createdAt, List<MessageDto> messages) {

  /**
   * @param status   {@code streaming}, {@code complete}, {@code failed} or {@code cancelled}
   * @param jobId    the job that produced or answered this message
   * @param metadata what the client needs to redraw the message as it looked live: contributors,
   *                 UI actions, the question asked
   */
  public record MessageDto(UUID id, String role, String content, Instant createdAt, String status,
                           UUID jobId, JsonNode metadata) {

    public MessageDto(UUID id, String role, String content, Instant createdAt) {
      this(id, role, content, createdAt, "complete", null, null);
    }
  }
}
