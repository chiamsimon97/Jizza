package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.RunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_runs")
public class WorkflowRunEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private UUID workflowId;

  @Column(nullable = false)
  private int version;

  private String triggerSource;

  @Column(nullable = false)
  private RunStatus status;

  private String currentNode;

  @Convert(converter = com.jizza.engine.persistence.converter.SealedJsonConverter.class)
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode context;

  private UUID boundTaskId;

  @Column(columnDefinition = "text")
  private String error;

  private boolean deadLettered;

  private Instant createdAt;

  private Instant updatedAt;

  @Version
  private long versionLock;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public UUID getWorkflowId() { return workflowId; }
  public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }

  public int getVersion() { return version; }
  public void setVersion(int version) { this.version = version; }

  public String getTriggerSource() { return triggerSource; }
  public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }

  public RunStatus getStatus() { return status; }
  public void setStatus(RunStatus status) { this.status = status; }

  public String getCurrentNode() { return currentNode; }
  public void setCurrentNode(String currentNode) { this.currentNode = currentNode; }

  public JsonNode getContext() { return context; }
  public void setContext(JsonNode context) { this.context = context; }

  public UUID getBoundTaskId() { return boundTaskId; }
  public void setBoundTaskId(UUID boundTaskId) { this.boundTaskId = boundTaskId; }

  public String getError() { return error; }
  public void setError(String error) { this.error = error; }

  public boolean isDeadLettered() { return deadLettered; }
  public void setDeadLettered(boolean deadLettered) { this.deadLettered = deadLettered; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

  public long getVersionLock() { return versionLock; }
  public void setVersionLock(long versionLock) { this.versionLock = versionLock; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void onUpdate() { updatedAt = Instant.now(); }
}
