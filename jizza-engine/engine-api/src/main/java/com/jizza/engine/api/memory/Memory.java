package com.jizza.engine.api.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import com.jizza.engine.api.enums.MemoryStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One remembered fact about the user or someone/something in their life.
 *
 * @param domains   agent domains that may see this fact; empty means every agent may
 * @param factKey   the attribute this fact sets on its subjects (e.g. {@code risk_tolerance}); two
 *                  active facts with the same key and subjects are a conflict, not two facts
 * @param subjectIds what the fact is about; empty means the user themselves
 * @param restricted never handed to an agent; shown to the user only after a fresh step-up
 * @param accessCount how many agent runs this fact has been handed to
 * @param score     retrieval relevance, set only on search results
 */
public record Memory(UUID id, UUID userId, MemoryCategory category, List<String> domains,
                     DataClassification classification, String content, String factKey,
                     JsonNode data, double confidence, double importance, MemorySource source,
                     String sourceRef, boolean confirmed, MemoryStatus status, UUID supersededBy,
                     List<UUID> subjectIds, Instant expiresAt, Instant createdAt,
                     Instant updatedAt, Instant lastAccessedAt, boolean restricted,
                     int accessCount, Double score) {

  /** The same fact with its words withheld: what the user sees of a restricted fact before step-up. */
  public Memory redacted() {
    return new Memory(id, userId, category, domains, classification, null, factKey, null,
        confidence, importance, source, sourceRef, confirmed, status, supersededBy, subjectIds,
        expiresAt, createdAt, updatedAt, lastAccessedAt, restricted, accessCount, score);
  }

  public Memory withScore(double s) {
    return new Memory(id, userId, category, domains, classification, content, factKey, data,
        confidence, importance, source, sourceRef, confirmed, status, supersededBy, subjectIds,
        expiresAt, createdAt, updatedAt, lastAccessedAt, restricted, accessCount, s);
  }
}
