package com.jizza.engine.persistence.entity;

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

/** What a note said, and which agent held it, before a change the user made. Deleted with the note. */
@Entity
@Table(name = "knowledge_history")
public class KnowledgeHistoryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID entryId;

  @Column(nullable = false)
  private UUID userId;

  /** {@code updated}, {@code moved}, {@code restricted} or {@code unrestricted}. */
  @Column(nullable = false)
  private String change;

  private String previousTitle;

  @Column(columnDefinition = "text")
  private String previousContent;

  private UUID previousAgentId;

  @Column(nullable = false)
  private ActorType actorType;

  private UUID actorId;

  private Instant createdAt;

  public UUID getId() { return id; }

  public UUID getEntryId() { return entryId; }
  public void setEntryId(UUID entryId) { this.entryId = entryId; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getChange() { return change; }
  public void setChange(String change) { this.change = change; }

  public String getPreviousTitle() { return previousTitle; }
  public void setPreviousTitle(String previousTitle) { this.previousTitle = previousTitle; }

  public String getPreviousContent() { return previousContent; }
  public void setPreviousContent(String previousContent) { this.previousContent = previousContent; }

  public UUID getPreviousAgentId() { return previousAgentId; }
  public void setPreviousAgentId(UUID previousAgentId) { this.previousAgentId = previousAgentId; }

  public ActorType getActorType() { return actorType; }
  public void setActorType(ActorType actorType) { this.actorType = actorType; }

  public UUID getActorId() { return actorId; }
  public void setActorId(UUID actorId) { this.actorId = actorId; }

  public Instant getCreatedAt() { return createdAt; }

  @PrePersist
  void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
}
