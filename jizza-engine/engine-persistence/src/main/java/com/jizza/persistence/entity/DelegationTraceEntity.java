package com.jizza.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delegation_traces")
public class DelegationTraceEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID messageId;

  private UUID fromAgent;

  private UUID toAgent;

  @Column(columnDefinition = "text")
  private String task;

  @Column(columnDefinition = "text")
  private String result;

  private Instant createdAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getMessageId() { return messageId; }
  public void setMessageId(UUID messageId) { this.messageId = messageId; }

  public UUID getFromAgent() { return fromAgent; }
  public void setFromAgent(UUID fromAgent) { this.fromAgent = fromAgent; }

  public UUID getToAgent() { return toAgent; }
  public void setToAgent(UUID toAgent) { this.toAgent = toAgent; }

  public String getTask() { return task; }
  public void setTask(String task) { this.task = task; }

  public String getResult() { return result; }
  public void setResult(String result) { this.result = result; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}
