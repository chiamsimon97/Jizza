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

/** One single-use two-factor recovery code, stored only as a hash. */
@Entity
@Table(name = "mfa_recovery_codes")
public class MfaRecoveryCodeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false, columnDefinition = "text")
  private String codeHash;

  private Instant usedAt;

  private Instant createdAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getCodeHash() { return codeHash; }
  public void setCodeHash(String codeHash) { this.codeHash = codeHash; }

  public Instant getUsedAt() { return usedAt; }
  public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }
}
