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

/** One piece of work the Activity panel shows: a chat turn, and later a scheduled plan step. */
@Entity
@Table(name = "jobs")
public class ChatJobEntity {

  public static final String RUNNING = "running";
  public static final String AWAITING_USER = "awaiting_user";
  public static final String COMPLETED = "completed";
  public static final String FAILED = "failed";
  public static final String CANCELLED = "cancelled";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  private UUID conversationId;

  private UUID planId;

  @Column(nullable = false)
  private String origin;

  private String title;

  @Column(columnDefinition = "text")
  private String summary;

  @Column(nullable = false)
  private String status;

  private UUID userMessageId;
  private UUID assistantMessageId;

  @Column(nullable = false)
  private int inputTokens;

  @Column(nullable = false)
  private int outputTokens;

  @Column(nullable = false)
  private Instant startedAt;

  private Instant finishedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }
  public UUID getConversationId() { return conversationId; }
  public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
  public String getOrigin() { return origin; }
  public void setOrigin(String origin) { this.origin = origin; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getSummary() { return summary; }
  public void setSummary(String summary) { this.summary = summary; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public UUID getUserMessageId() { return userMessageId; }
  public void setUserMessageId(UUID userMessageId) { this.userMessageId = userMessageId; }
  public UUID getAssistantMessageId() { return assistantMessageId; }
  public void setAssistantMessageId(UUID assistantMessageId) { this.assistantMessageId = assistantMessageId; }
  public int getInputTokens() { return inputTokens; }
  public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
  public int getOutputTokens() { return outputTokens; }
  public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
  public Instant getStartedAt() { return startedAt; }
  public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
  public Instant getFinishedAt() { return finishedAt; }
  public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

  @PrePersist
  void onCreate() {
    if (startedAt == null) startedAt = Instant.now();
  }

  public UUID getPlanId() { return planId; }
  public void setPlanId(UUID planId) { this.planId = planId; }
}
