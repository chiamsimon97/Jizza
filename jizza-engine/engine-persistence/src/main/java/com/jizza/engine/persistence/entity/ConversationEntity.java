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

@Entity
@Table(name = "conversations")
public class ConversationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  private Instant createdAt;

  private String title;

  /**
   * {@code auto} titles (the first words typed) are replaced by a {@code model} one after the first
   * exchange; {@code user} titles are never replaced.
   */
  @Column(nullable = false)
  private String titleSource = "auto";

  @Column(nullable = false)
  private boolean pinned;

  private Instant archivedAt;

  private Instant lastMessageAt;

  @Column(nullable = false)
  private int messageCount;

  /** What the conversation said before {@link #summaryThrough}, folded into prose. */
  private String summary;

  private Instant summaryThrough;

  public String getSummary() { return summary; }
  public void setSummary(String summary) { this.summary = summary; }

  public Instant getSummaryThrough() { return summaryThrough; }
  public void setSummaryThrough(Instant summaryThrough) { this.summaryThrough = summaryThrough; }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }

  public String getTitleSource() { return titleSource; }
  public void setTitleSource(String titleSource) { this.titleSource = titleSource; }

  public boolean isPinned() { return pinned; }
  public void setPinned(boolean pinned) { this.pinned = pinned; }

  public Instant getArchivedAt() { return archivedAt; }
  public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }

  public Instant getLastMessageAt() { return lastMessageAt; }
  public void setLastMessageAt(Instant lastMessageAt) { this.lastMessageAt = lastMessageAt; }

  public int getMessageCount() { return messageCount; }
  public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}
