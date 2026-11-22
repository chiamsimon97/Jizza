package com.jizza.engine.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * How the user rated replies: thumbs up and down for each agent that answered, and the latest
 * rated replies with the reason given, newest first.
 */
public record ReplyQualityDto(int days, List<AgentQuality> agents, List<Rated> recent) {

  /** One agent's ratings; {@code agentId} is null for a reply whose agent no longer exists. */
  public record AgentQuality(UUID agentId, String key, String name, int up, int down) {}

  /** A rated reply: {@code rating} is {@code up} or {@code down}; {@code excerpt} its opening words. */
  public record Rated(UUID conversationId, UUID messageId, String conversationTitle, String agentKey,
                      String agentName, String rating, String reason, String excerpt, Instant at) {}
}
