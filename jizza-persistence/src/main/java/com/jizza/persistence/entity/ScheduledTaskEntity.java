package com.jizza.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.CatchUpPolicy;
import com.jizza.common.enums.ScheduleType;
import com.jizza.common.enums.TaskStatus;
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
@Table(name = "scheduled_tasks")
public class ScheduledTaskEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private UUID agentId;

  @Column(nullable = false)
  private String title;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode payload;

  @Column(nullable = false)
  private ScheduleType scheduleType;

  @Column(columnDefinition = "text")
  private String cronOrRrule;

  private Instant runAt;

  private Instant nextRunAt;

  private String timezone;

  private UUID boundWorkflowId;

  private Integer boundVersion;

  @Column(nullable = false)
  private TaskStatus status;

  private Instant lastRunAt;

  @Column(columnDefinition = "text")
  private String lastResult;

  private boolean repetitive;

  private CatchUpPolicy catchUpPolicy;

  private Instant createdAt;

  @Version
  private long version;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public UUID getAgentId() { return agentId; }
  public void setAgentId(UUID agentId) { this.agentId = agentId; }

  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }

  public JsonNode getPayload() { return payload; }
  public void setPayload(JsonNode payload) { this.payload = payload; }

  public ScheduleType getScheduleType() { return scheduleType; }
  public void setScheduleType(ScheduleType scheduleType) { this.scheduleType = scheduleType; }

  public String getCronOrRrule() { return cronOrRrule; }
  public void setCronOrRrule(String cronOrRrule) { this.cronOrRrule = cronOrRrule; }

  public Instant getRunAt() { return runAt; }
  public void setRunAt(Instant runAt) { this.runAt = runAt; }

  public Instant getNextRunAt() { return nextRunAt; }
  public void setNextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; }

  public String getTimezone() { return timezone; }
  public void setTimezone(String timezone) { this.timezone = timezone; }

  public UUID getBoundWorkflowId() { return boundWorkflowId; }
  public void setBoundWorkflowId(UUID boundWorkflowId) { this.boundWorkflowId = boundWorkflowId; }

  public Integer getBoundVersion() { return boundVersion; }
  public void setBoundVersion(Integer boundVersion) { this.boundVersion = boundVersion; }

  public TaskStatus getStatus() { return status; }
  public void setStatus(TaskStatus status) { this.status = status; }

  public Instant getLastRunAt() { return lastRunAt; }
  public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }

  public String getLastResult() { return lastResult; }
  public void setLastResult(String lastResult) { this.lastResult = lastResult; }

  public boolean getRepetitive() { return repetitive; }
  public void setRepetitive(boolean repetitive) { this.repetitive = repetitive; }

  public CatchUpPolicy getCatchUpPolicy() { return catchUpPolicy; }
  public void setCatchUpPolicy(CatchUpPolicy catchUpPolicy) { this.catchUpPolicy = catchUpPolicy; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public long getVersion() { return version; }
  public void setVersion(long version) { this.version = version; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}
