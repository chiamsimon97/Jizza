package com.jizza.engine.api.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A job as the Activity panel shows it. {@code events} is empty in listings and filled when one job
 * is opened; {@code partialText} is the reply written so far while the job is still running.
 */
public record JobDto(UUID id, UUID conversationId, String origin, String title, String summary,
                     String status, UUID userMessageId, UUID assistantMessageId,
                     Instant startedAt, Instant finishedAt, List<JobEventDto> events,
                     String partialText) {

  public JobDto {
    events = events == null ? List.of() : List.copyOf(events);
  }
}
