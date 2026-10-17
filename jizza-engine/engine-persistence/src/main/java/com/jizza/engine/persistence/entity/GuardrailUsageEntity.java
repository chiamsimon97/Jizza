package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One call a budgeted guardrail let through, counted against its budget and call limit. */
@Entity
@Table(name = "guardrail_usage")
public class GuardrailUsageEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID policyId;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String toolRef;

  private BigDecimal amount;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getPolicyId() { return policyId; }
  public void setPolicyId(UUID policyId) { this.policyId = policyId; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getToolRef() { return toolRef; }
  public void setToolRef(String toolRef) { this.toolRef = toolRef; }

  public BigDecimal getAmount() { return amount; }
  public void setAmount(BigDecimal amount) { this.amount = amount; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
