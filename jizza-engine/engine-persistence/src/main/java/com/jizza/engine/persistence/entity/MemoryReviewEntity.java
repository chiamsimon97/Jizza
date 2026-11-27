package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One pass of the memory review over one user's recent activity. */
@Entity
@Table(name = "memory_reviews")
public class MemoryReviewEntity {

  @Id
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  /** {@code nightly} or {@code manual}. */
  @Column(nullable = false)
  private String trigger;

  @Column(nullable = false)
  private Instant windowStart;

  @Column(nullable = false)
  private Instant windowEnd;

  /** {@code running}, {@code done}, {@code skipped} or {@code failed}. */
  @Column(nullable = false)
  private String status;

  @Column(columnDefinition = "text")
  private String detail;

  private int proposals;

  private int inputTokens;

  private int outputTokens;

  @Column(nullable = false)
  private Instant startedAt;

  private Instant finishedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getTrigger() { return trigger; }
  public void setTrigger(String trigger) { this.trigger = trigger; }

  public Instant getWindowStart() { return windowStart; }
  public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }

  public Instant getWindowEnd() { return windowEnd; }
  public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public String getDetail() { return detail; }
  public void setDetail(String detail) { this.detail = detail; }

  public int getProposals() { return proposals; }
  public void setProposals(int proposals) { this.proposals = proposals; }

  public int getInputTokens() { return inputTokens; }
  public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }

  public int getOutputTokens() { return outputTokens; }
  public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }

  public Instant getStartedAt() { return startedAt; }
  public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

  public Instant getFinishedAt() { return finishedAt; }
  public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
