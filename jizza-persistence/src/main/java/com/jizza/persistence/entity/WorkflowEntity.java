package com.jizza.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.WorkflowOrigin;
import com.jizza.common.enums.WorkflowStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflows")
public class WorkflowEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String name;

  @Column(columnDefinition = "text")
  private String description;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private JsonNode graph;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode inputSchema;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode triggerConfig;

  @Column(nullable = false)
  private WorkflowOrigin origin;

  @Column(nullable = false)
  private WorkflowStatus status;

  private UUID owningAgentId;

  @Column(nullable = false)
  private int version;

  private UUID sourceTaskId;

  private boolean disabled;

  @Column(columnDefinition = "text")
  private String reviewFeedback;

  @Column(columnDefinition = "text")
  private String graphFingerprint;

  private Instant createdAt;

  @Version
  private long versionLock;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }

  public JsonNode getGraph() { return graph; }
  public void setGraph(JsonNode graph) { this.graph = graph; }

  public JsonNode getInputSchema() { return inputSchema; }
  public void setInputSchema(JsonNode inputSchema) { this.inputSchema = inputSchema; }

  public JsonNode getTriggerConfig() { return triggerConfig; }
  public void setTriggerConfig(JsonNode triggerConfig) { this.triggerConfig = triggerConfig; }

  public WorkflowOrigin getOrigin() { return origin; }
  public void setOrigin(WorkflowOrigin origin) { this.origin = origin; }

  public WorkflowStatus getStatus() { return status; }
  public void setStatus(WorkflowStatus status) { this.status = status; }

  public UUID getOwningAgentId() { return owningAgentId; }
  public void setOwningAgentId(UUID owningAgentId) { this.owningAgentId = owningAgentId; }

  public int getVersion() { return version; }
  public void setVersion(int version) { this.version = version; }

  public UUID getSourceTaskId() { return sourceTaskId; }
  public void setSourceTaskId(UUID sourceTaskId) { this.sourceTaskId = sourceTaskId; }

  public boolean getDisabled() { return disabled; }
  public void setDisabled(boolean disabled) { this.disabled = disabled; }

  public String getReviewFeedback() { return reviewFeedback; }
  public void setReviewFeedback(String reviewFeedback) { this.reviewFeedback = reviewFeedback; }

  public String getGraphFingerprint() { return graphFingerprint; }
  public void setGraphFingerprint(String graphFingerprint) { this.graphFingerprint = graphFingerprint; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public long getVersionLock() { return versionLock; }
  public void setVersionLock(long versionLock) { this.versionLock = versionLock; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}
