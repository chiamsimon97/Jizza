package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A command for a device; inputs and outputs are encrypted. */
@Entity
@Table(name = "device_commands")
public class DeviceCommandEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID deviceId;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private UUID workspaceId;

  private UUID runId;

  private String nodeId;

  @Column(nullable = false)
  private String action;

  private int actionVersion;

  private byte[] input;

  @Column(nullable = false)
  private String status;

  private boolean interactive;

  private String idempotencyKey;

  @Column(columnDefinition = "text")
  private String signature;

  @Column(nullable = false)
  private String nonce;

  @Column(nullable = false)
  private Instant deadlineAt;

  @Column(columnDefinition = "text")
  private String progress;

  private byte[] output;

  private boolean outputMismatch;

  private String errorCode;

  @Column(columnDefinition = "text")
  private String error;

  @Column(nullable = false)
  private Instant createdAt;

  private Instant dispatchedAt;

  private Instant acceptedAt;

  private Instant finishedAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getDeviceId() { return deviceId; }
  public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public UUID getWorkspaceId() { return workspaceId; }
  public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }

  public UUID getRunId() { return runId; }
  public void setRunId(UUID runId) { this.runId = runId; }

  public String getNodeId() { return nodeId; }
  public void setNodeId(String nodeId) { this.nodeId = nodeId; }

  public String getAction() { return action; }
  public void setAction(String action) { this.action = action; }

  public int getActionVersion() { return actionVersion; }
  public void setActionVersion(int actionVersion) { this.actionVersion = actionVersion; }

  public byte[] getInput() { return input; }
  public void setInput(byte[] input) { this.input = input; }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public boolean isInteractive() { return interactive; }
  public void setInteractive(boolean interactive) { this.interactive = interactive; }

  public String getIdempotencyKey() { return idempotencyKey; }
  public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

  public String getSignature() { return signature; }
  public void setSignature(String signature) { this.signature = signature; }

  public String getNonce() { return nonce; }
  public void setNonce(String nonce) { this.nonce = nonce; }

  public Instant getDeadlineAt() { return deadlineAt; }
  public void setDeadlineAt(Instant deadlineAt) { this.deadlineAt = deadlineAt; }

  public String getProgress() { return progress; }
  public void setProgress(String progress) { this.progress = progress; }

  public byte[] getOutput() { return output; }
  public void setOutput(byte[] output) { this.output = output; }

  public boolean isOutputMismatch() { return outputMismatch; }
  public void setOutputMismatch(boolean outputMismatch) { this.outputMismatch = outputMismatch; }

  public String getErrorCode() { return errorCode; }
  public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

  public String getError() { return error; }
  public void setError(String error) { this.error = error; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getDispatchedAt() { return dispatchedAt; }
  public void setDispatchedAt(Instant dispatchedAt) { this.dispatchedAt = dispatchedAt; }

  public Instant getAcceptedAt() { return acceptedAt; }
  public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }

  public Instant getFinishedAt() { return finishedAt; }
  public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
