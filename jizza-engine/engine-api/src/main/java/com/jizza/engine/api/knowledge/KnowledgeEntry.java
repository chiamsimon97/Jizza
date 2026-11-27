package com.jizza.engine.api.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.DataClassification;
import java.time.Instant;
import java.util.UUID;

/**
 * A single namespaced knowledge record. {@code agentId} <em>is</em> the namespace; the store rejects
 * any attempt to read or write across it (R22).
 *
 * @param restricted never handed to an agent, its owner included; the user sees it after step-up
 * @param lesson     set when this is a lesson (how-to the user approved), null for a plain note
 */
public record KnowledgeEntry(UUID id, UUID userId, UUID agentId, String title, String content,
                             JsonNode data, DataClassification classification,
                             Instant createdAt, Instant updatedAt, Double score,
                             boolean restricted, Lesson lesson) {

  /**
   * What a lesson is about and how often it went wrong before it was learned.
   *
   * @param aboutKey    {@code tool:<tool name>}, {@code site:<host>}, {@code workflow:<id>} or
   *                    {@code general}
   * @param aboutLabel  the same in the user's words ("IRCTC website")
   * @param occurrences how many failures it was learned from
   */
  public record Lesson(String aboutKey, String aboutLabel, int occurrences) {}

  public KnowledgeEntry(UUID id, UUID userId, UUID agentId, String title, String content,
                        JsonNode data, DataClassification classification, Instant createdAt,
                        Instant updatedAt, Double score, boolean restricted) {
    this(id, userId, agentId, title, content, data, classification, createdAt, updatedAt, score,
        restricted, null);
  }

  public KnowledgeEntry(UUID id, UUID userId, UUID agentId, String title, String content,
                        JsonNode data, DataClassification classification, Instant createdAt,
                        Instant updatedAt, Double score) {
    this(id, userId, agentId, title, content, data, classification, createdAt, updatedAt, score, false);
  }

  public static KnowledgeEntry create(UUID userId, UUID agentId, String title, String content,
                                      JsonNode data, DataClassification classification) {
    return new KnowledgeEntry(null, userId, agentId, title, content, data, classification, null, null, null);
  }

  public boolean isLesson() { return lesson != null; }

  public KnowledgeEntry withScore(double s) {
    return new KnowledgeEntry(id, userId, agentId, title, content, data, classification, createdAt,
        updatedAt, s, restricted, lesson);
  }
}
