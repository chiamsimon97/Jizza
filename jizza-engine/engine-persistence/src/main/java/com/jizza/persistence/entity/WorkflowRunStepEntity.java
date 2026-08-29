package com.jizza.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.RunStepStatus;
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
@Table(name = "workflow_run_steps")
public class WorkflowRunStepEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID runId;

  @Column(nullable = false)
  private String nodeId;

  @Column(nullable = false)
  private RunStepStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode output;

  private int attempts;

  private Instant startedAt;

  private Instant finishedAt;

  @Column(columnDefinition = "text")
  private String error;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getRunId() { return runId; }
  public void setRunId(UUID runId) { this.runId = runId; }

  public String getNodeId() { return nodeId; }
  public void setNodeId(String nodeId) { this.nodeId = nodeId; }

  public RunStepStatus getStatus() { return status; }
  public void setStatus(RunStepStatus status) { this.status = status; }

  public JsonNode getOutput() { return output; }
  public void setOutput(JsonNode output) { this.output = output; }

  public int getAttempts() { return attempts; }
  public void setAttempts(int attempts) { this.attempts = attempts; }

  public Instant getStartedAt() { return startedAt; }
  public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

  public Instant getFinishedAt() { return finishedAt; }
  public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

  public String getError() { return error; }
  public void setError(String error) { this.error = error; }

  @PrePersist
  void onCreate() {
    if (startedAt == null) startedAt = Instant.now();
  }
}
