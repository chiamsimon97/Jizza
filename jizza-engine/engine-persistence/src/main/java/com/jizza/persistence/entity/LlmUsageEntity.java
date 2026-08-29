package com.jizza.persistence.entity;

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

  private int inputTokens;

  private int outputTokens;

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
