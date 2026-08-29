package com.jizza.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.IdempotencyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(name = "key", nullable = false)
  private String key;

  private String tool;

  @Column(columnDefinition = "text")
  private String fingerprint;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode result;

  @Column(nullable = false)
  private IdempotencyStatus status;

  private Instant createdAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getKey() { return key; }
  public void setKey(String key) { this.key = key; }

  public String getTool() { return tool; }
  public void setTool(String tool) { this.tool = tool; }

  public String getFingerprint() { return fingerprint; }
  public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

  public JsonNode getResult() { return result; }
  public void setResult(JsonNode result) { this.result = result; }

  public IdempotencyStatus getStatus() { return status; }
  public void setStatus(IdempotencyStatus status) { this.status = status; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}
