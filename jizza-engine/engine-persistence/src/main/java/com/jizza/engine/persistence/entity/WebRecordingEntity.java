package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A recording while it is captured and reviewed, before it becomes a workflow: in a browser
 * ({@code web}) or in Windows apps ({@code desktop}).
 */
@Entity
@Table(name = "web_recordings")
public class WebRecordingEntity {

  @Id
  private UUID id;

  @Column(nullable = false)
  private UUID ownerId;

  private UUID workspaceId;
  private UUID deviceId;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String kind = "web";

  private String app;

  @Column(columnDefinition = "text")
  private String startUrl;

  @Column(nullable = false)
  private String status;

  private UUID commandId;
  private UUID workflowId;
  /** Edit recording: the saved recording (workflow) this was opened from, and its version then. */
  private UUID sourceWorkflowId;
  private Integer sourceVersion;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(columnDefinition = "text[]", nullable = false)
  private String[] sites = new String[0];

  private int stepCount;

  @Column(nullable = false)
  private Instant startedAt;

  private Instant stoppedAt;

  @Column(nullable = false)
  private Instant updatedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public UUID getOwnerId() { return ownerId; }
  public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
  public UUID getWorkspaceId() { return workspaceId; }
  public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }
  public UUID getDeviceId() { return deviceId; }
  public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }
  public UUID getSourceWorkflowId() { return sourceWorkflowId; }
  public void setSourceWorkflowId(UUID sourceWorkflowId) { this.sourceWorkflowId = sourceWorkflowId; }
  public Integer getSourceVersion() { return sourceVersion; }
  public void setSourceVersion(Integer sourceVersion) { this.sourceVersion = sourceVersion; }
  public String getApp() { return app; }
  public void setApp(String app) { this.app = app; }
  public String getStartUrl() { return startUrl; }
  public void setStartUrl(String startUrl) { this.startUrl = startUrl; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public UUID getCommandId() { return commandId; }
  public void setCommandId(UUID commandId) { this.commandId = commandId; }
  public UUID getWorkflowId() { return workflowId; }
  public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }
  public String[] getSites() { return sites; }
  public void setSites(String[] sites) { this.sites = sites == null ? new String[0] : sites; }
  public int getStepCount() { return stepCount; }
  public void setStepCount(int stepCount) { this.stepCount = stepCount; }
  public Instant getStartedAt() { return startedAt; }
  public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
  public Instant getStoppedAt() { return stoppedAt; }
  public void setStoppedAt(Instant stoppedAt) { this.stoppedAt = stoppedAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
