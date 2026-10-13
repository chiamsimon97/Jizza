package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
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

/** One action run, trigger poll or test against a service, for its History tab. */
@Entity
@Table(name = "service_runs")
public class ServiceRunEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID connectionId;

  @Column(nullable = false)
  private String kind;

  private UUID refId;

  private String refName;

  @Column(nullable = false)
  private String source;

  @Column(nullable = false)
  private String status;

  private Long durationMs;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode input;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode output;

  @Column(columnDefinition = "text")
  private String error;

  private Instant createdAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getConnectionId() { return connectionId; }
  public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }

  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }

  public UUID getRefId() { return refId; }
  public void setRefId(UUID refId) { this.refId = refId; }

  public String getRefName() { return refName; }
  public void setRefName(String refName) { this.refName = refName; }

  public String getSource() { return source; }
  public void setSource(String source) { this.source = source; }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public Long getDurationMs() { return durationMs; }
  public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

  public JsonNode getInput() { return input; }
  public void setInput(JsonNode input) { this.input = input; }

  public JsonNode getOutput() { return output; }
  public void setOutput(JsonNode output) { this.output = output; }

  public String getError() { return error; }
  public void setError(String error) { this.error = error; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}
