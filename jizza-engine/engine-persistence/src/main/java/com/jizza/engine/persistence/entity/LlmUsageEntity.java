package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_usage")
public class LlmUsageEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String model;

  private String purpose;

  /** {@code platform} or {@code own} (the person's own key). */
  @Column(nullable = false)
  private String paidBy = PLATFORM;

  public static final String PLATFORM = "platform";
  public static final String OWN = "own";

  public String getPaidBy() { return paidBy; }
  public void setPaidBy(String v) { this.paidBy = v; }

  private int inputTokens;

  private int outputTokens;

  /** The part of {@code inputTokens} read from the provider's prompt cache. */
  private int cacheReadTokens;

  /** The part of {@code inputTokens} written to the provider's prompt cache. */
  private int cacheWriteTokens;

  private BigDecimal costUnits;

  private Instant createdAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getModel() { return model; }
  public void setModel(String model) { this.model = model; }

  public String getPurpose() { return purpose; }
  public void setPurpose(String purpose) { this.purpose = purpose; }

  public int getInputTokens() { return inputTokens; }
  public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }

  public int getOutputTokens() { return outputTokens; }
  public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }

  public int getCacheReadTokens() { return cacheReadTokens; }
  public void setCacheReadTokens(int cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }

  public int getCacheWriteTokens() { return cacheWriteTokens; }
  public void setCacheWriteTokens(int cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }

  public BigDecimal getCostUnits() { return costUnits; }
  public void setCostUnits(BigDecimal costUnits) { this.costUnits = costUnits; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}
