package com.jizza.engine.api.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.DataClassification;
import java.time.Instant;
import java.util.UUID;

/**
 * A single namespaced knowledge record. {@code agentId} <em>is</em> the namespace; the store rejects
 * any attempt to read or write across it (R22).
 */
public record KnowledgeEntry(UUID id, UUID userId, UUID agentId, String title, String content,
                             JsonNode data, DataClassification classification,
                             Instant createdAt, Instant updatedAt, Double score) {

  public static KnowledgeEntry create(UUID userId, UUID agentId, String title, String content,
                                      JsonNode data, DataClassification classification) {
    return new KnowledgeEntry(null, userId, agentId, title, content, data, classification, null, null, null);
  }

  public KnowledgeEntry withScore(double s) {
    return new KnowledgeEntry(id, userId, agentId, title, content, data, classification, createdAt, updatedAt, s);
  }
}
