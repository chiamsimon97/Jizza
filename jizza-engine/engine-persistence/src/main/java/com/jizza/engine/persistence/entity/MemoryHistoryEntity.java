package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActorType;
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
@Table(name = "memory_history")
public class MemoryHistoryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID memoryId;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String change;

  @Column(columnDefinition = "text")
  private String previousContent;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode previousData;

  @Column(nullable = false)
  private ActorType actorType;

  private UUID actorId;

  @Column(columnDefinition = "text")
  private String note;

  private Instant createdAt;

  public UUID getId() { return id; }

  public UUID getMemoryId() { return memoryId; }
  public void setMemoryId(UUID memoryId) { this.memoryId = memoryId; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getChange() { return change; }
  public void setChange(String change) { this.change = change; }

  public String getPreviousContent() { return previousContent; }
  public void setPreviousContent(String previousContent) { this.previousContent = previousContent; }

  public JsonNode getPreviousData() { return previousData; }
  public void setPreviousData(JsonNode previousData) { this.previousData = previousData; }

  public ActorType getActorType() { return actorType; }
  public void setActorType(ActorType actorType) { this.actorType = actorType; }

  public UUID getActorId() { return actorId; }
  public void setActorId(UUID actorId) { this.actorId = actorId; }

  public String getNote() { return note; }
  public void setNote(String note) { this.note = note; }

  public Instant getCreatedAt() { return createdAt; }

  @PrePersist
  void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
}
