package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.JobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "job_queue")
public class JobEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID userId;

  @Column(nullable = false)
  private String type;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode payload;

  @Column(nullable = false)
  private JobStatus status;

  @Column(nullable = false)
  private Instant runAfter;

  private int attempts;

  private int maxAttempts;

  @Column(columnDefinition = "text")
  private String lastError;

  private String leasedBy;

  private Instant leasedUntil;

  private Instant createdAt;

  private Instant updatedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getType() { return type; }
  public void setType(String type) { this.type = type; }

  public JsonNode getPayload() { return payload; }
  public void setPayload(JsonNode payload) { this.payload = payload; }

  public JobStatus getStatus() { return status; }
  public void setStatus(JobStatus status) { this.status = status; }

  public Instant getRunAfter() { return runAfter; }
  public void setRunAfter(Instant runAfter) { this.runAfter = runAfter; }

  public int getAttempts() { return attempts; }
  public void setAttempts(int attempts) { this.attempts = attempts; }

  public int getMaxAttempts() { return maxAttempts; }
  public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

  public String getLastError() { return lastError; }
  public void setLastError(String lastError) { this.lastError = lastError; }

  public String getLeasedBy() { return leasedBy; }
  public void setLeasedBy(String leasedBy) { this.leasedBy = leasedBy; }

  public Instant getLeasedUntil() { return leasedUntil; }
  public void setLeasedUntil(Instant leasedUntil) { this.leasedUntil = leasedUntil; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
    if (runAfter == null) runAfter = now;
  }

  @PreUpdate
  void onUpdate() { updatedAt = Instant.now(); }
}
