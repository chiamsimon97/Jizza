package com.jizza.engine.api.cosmos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One memory item as a satellite: a fact from the user's memory or a note from an agent's own.
 *
 * @param itemKind      {@code fact} or {@code note}
 * @param parentBodyId  the body it orbits
 * @param label         the first 120 characters; null when {@code redacted}
 * @param agentKey      whose colour it takes: the agent it belongs to, or the first that can see it
 * @param category      a fact's category; {@code note} for notes
 * @param tetherBodyIds the other bodies this item reaches: every other planet that can see a fact,
 *                      and every other subject it is about
 */
public record MemoryNode(String itemKind, UUID id, String parentBodyId, String label,
                         String agentKey, String category, String classification,
                         boolean restricted, boolean redacted, String status, boolean confirmed,
                         int accessCount, Instant lastAccessedAt, Instant createdAt,
                         List<String> tetherBodyIds) {

  public static final String FACT = "fact";
  public static final String NOTE = "note";
}
