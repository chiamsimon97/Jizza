package com.jizza.engine.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A conversation as the conversation list shows it.
 *
 * @param kind      {@code main} for a chat with Jizza, {@code thread} for a topic handed to a lead
 * @param parentId  for a thread, the main chat it was opened from
 * @param lead      for a thread, the agent leading it (Jizza when no specialist does)
 * @param topic     for a thread, what it is about
 */
public record ConversationSummaryDto(UUID id, String title, String preview, Instant createdAt,
                                     Instant lastMessageAt, boolean pinned, boolean archived,
                                     int messageCount, String kind, UUID parentId, AgentRef lead,
                                     String topic) {

  /** An agent as a list row needs it: its id, the key its logo is drawn from and its name. */
  public record AgentRef(UUID id, String key, String name) {}
}
