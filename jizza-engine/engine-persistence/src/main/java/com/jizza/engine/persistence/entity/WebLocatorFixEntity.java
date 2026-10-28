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
 * A suggested new element for a web step that could no longer find its own. It waits for the
 * person to apply or dismiss it; nothing changes the workflow on its own.
 */
@Entity
@Table(name = "web_locator_fixes")
public class WebLocatorFixEntity {

  @Id
  private UUID id;

  @Column(nullable = false)
  private UUID ownerId;

  @Column(nullable = false)
  private UUID workflowId;

  @Column(nullable = false)
  private String nodeId;

  private UUID runId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode candidates;

  @Column(nullable = false)
  private String status;

  @Column(nullable = false)
  private Instant createdAt;

  private Instant decidedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public UUID getOwnerId() { return ownerId; }
  public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
  public UUID getWorkflowId() { return workflowId; }
  public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }
  public String getNodeId() { return nodeId; }
  public void setNodeId(String nodeId) { this.nodeId = nodeId; }
  public UUID getRunId() { return runId; }
  public void setRunId(UUID runId) { this.runId = runId; }
  public JsonNode getCandidates() { return candidates; }
  public void setCandidates(JsonNode candidates) { this.candidates = candidates; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
  public Instant getDecidedAt() { return decidedAt; }
  public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
