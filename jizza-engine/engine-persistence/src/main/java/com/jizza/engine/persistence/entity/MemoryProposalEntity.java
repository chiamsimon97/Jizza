package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A change the memory review suggests: merge facts, confirm one, retire one, reword one, or teach an
 * agent a lesson. It waits for the user; nothing changes memory until they approve it.
 */
@Entity
@Table(name = "memory_proposals")
public class MemoryProposalEntity {

  @Id
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  private UUID reviewId;

  /** {@code merge}, {@code confirm}, {@code retire}, {@code update} or {@code lesson}. */
  @Column(nullable = false)
  private String kind;

  /** {@code open}, {@code approved}, {@code dismissed}, {@code expired} or {@code stale}. */
  @Column(nullable = false)
  private String status;

  @Column(nullable = false, columnDefinition = "text")
  private String summary;

  @Column(columnDefinition = "text")
  private String reason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode payload;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode evidence;

  private int occurrences;

  @Column(nullable = false)
  private String fingerprint;

  private String resultRef;

  @Column(nullable = false)
  private Instant createdAt;

  private Instant decidedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public UUID getReviewId() { return reviewId; }
  public void setReviewId(UUID reviewId) { this.reviewId = reviewId; }

  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public String getSummary() { return summary; }
  public void setSummary(String summary) { this.summary = summary; }

  public String getReason() { return reason; }
  public void setReason(String reason) { this.reason = reason; }

  public JsonNode getPayload() { return payload; }
  public void setPayload(JsonNode payload) { this.payload = payload; }

  public JsonNode getEvidence() { return evidence; }
  public void setEvidence(JsonNode evidence) { this.evidence = evidence; }

  public int getOccurrences() { return occurrences; }
  public void setOccurrences(int occurrences) { this.occurrences = occurrences; }

  public String getFingerprint() { return fingerprint; }
  public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

  public String getResultRef() { return resultRef; }
  public void setResultRef(String resultRef) { this.resultRef = resultRef; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getDecidedAt() { return decidedAt; }
  public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
