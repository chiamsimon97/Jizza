package com.jizza.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.WorkflowStatus;
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
@Table(name = "workflow_versions")
public class WorkflowVersionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID workflowId;

  @Column(nullable = false)
  private int version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode graph;

  @Column(nullable = false)
  private WorkflowStatus status;

  @Column(columnDefinition = "text")
  private String graphFingerprint;

  private Instant createdAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getWorkflowId() { return workflowId; }
  public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }

  public int getVersion() { return version; }
  public void setVersion(int version) { this.version = version; }

  public JsonNode getGraph() { return graph; }
  public void setGraph(JsonNode graph) { this.graph = graph; }

  public WorkflowStatus getStatus() { return status; }
  public void setStatus(WorkflowStatus status) { this.status = status; }

  public String getGraphFingerprint() { return graphFingerprint; }
  public void setGraphFingerprint(String graphFingerprint) { this.graphFingerprint = graphFingerprint; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}
