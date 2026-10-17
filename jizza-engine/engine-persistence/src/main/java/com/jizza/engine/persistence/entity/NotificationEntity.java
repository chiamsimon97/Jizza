package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.EscalationState;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationPriority;
import com.jizza.engine.api.enums.NotificationStatus;
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
@Table(name = "notifications")
public class NotificationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  private UUID sourceAgentId;

  @Column(nullable = false)
  private NotificationKind kind;

  @Column(columnDefinition = "text")
  private String body;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode context;

  private UUID taskId;

  private UUID workflowId;

  private UUID runId;

  @Column(nullable = false)
  private NotificationStatus status;

  @Column(columnDefinition = "text")
  private String submittedInstruction;

  private Instant expiresAt;

  private EscalationState escalationState;

  private int reminderCount;

  private Instant lastRemindedAt;

  private Instant createdAt;

  private Instant answeredAt;

  @Column(nullable = false)
  private NotificationPriority priority = NotificationPriority.NORMAL;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public UUID getSourceAgentId() { return sourceAgentId; }
  public void setSourceAgentId(UUID sourceAgentId) { this.sourceAgentId = sourceAgentId; }

  public NotificationKind getKind() { return kind; }
  public void setKind(NotificationKind kind) { this.kind = kind; }

  public String getBody() { return body; }
  public void setBody(String body) { this.body = body; }

  public JsonNode getContext() { return context; }
  public void setContext(JsonNode context) { this.context = context; }

  public UUID getTaskId() { return taskId; }
  public void setTaskId(UUID taskId) { this.taskId = taskId; }

  public UUID getWorkflowId() { return workflowId; }
  public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }

  public UUID getRunId() { return runId; }
  public void setRunId(UUID runId) { this.runId = runId; }

  public NotificationStatus getStatus() { return status; }
  public void setStatus(NotificationStatus status) { this.status = status; }

  public String getSubmittedInstruction() { return submittedInstruction; }
  public void setSubmittedInstruction(String submittedInstruction) { this.submittedInstruction = submittedInstruction; }

  public Instant getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

  public EscalationState getEscalationState() { return escalationState; }
  public void setEscalationState(EscalationState escalationState) { this.escalationState = escalationState; }

  public int getReminderCount() { return reminderCount; }
  public void setReminderCount(int reminderCount) { this.reminderCount = reminderCount; }

  public Instant getLastRemindedAt() { return lastRemindedAt; }
  public void setLastRemindedAt(Instant lastRemindedAt) { this.lastRemindedAt = lastRemindedAt; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getAnsweredAt() { return answeredAt; }
  public void setAnsweredAt(Instant answeredAt) { this.answeredAt = answeredAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }

  public NotificationPriority getPriority() { return priority; }
  public void setPriority(NotificationPriority priority) { this.priority = priority; }
}
