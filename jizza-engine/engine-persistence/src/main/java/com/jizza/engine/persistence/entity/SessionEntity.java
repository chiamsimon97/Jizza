package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sessions")
public class SessionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(columnDefinition = "text")
  private String device;

  @JdbcTypeCode(SqlTypes.INET)
  private InetAddress ip;

  private Instant issuedAt;

  @Column(nullable = false)
  private Instant idleExpiresAt;

  @Column(nullable = false)
  private Instant absoluteExpiresAt;

  private boolean revoked;

  private Instant stepUpAt;

  private Instant lastSeenAt;

  /** SHA-256 of the cookie token; the token itself is never stored. */
  @Column(columnDefinition = "text")
  private String tokenHash;

  private boolean mfaSatisfied;

  private int stepUpFailures;

  /** The account this session is acting in. */
  private UUID activeWorkspaceId;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getDevice() { return device; }
  public void setDevice(String device) { this.device = device; }

  public InetAddress getIp() { return ip; }
  public void setIp(InetAddress ip) { this.ip = ip; }

  public Instant getIssuedAt() { return issuedAt; }
  public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

  public Instant getIdleExpiresAt() { return idleExpiresAt; }
  public void setIdleExpiresAt(Instant idleExpiresAt) { this.idleExpiresAt = idleExpiresAt; }

  public Instant getAbsoluteExpiresAt() { return absoluteExpiresAt; }
  public void setAbsoluteExpiresAt(Instant absoluteExpiresAt) { this.absoluteExpiresAt = absoluteExpiresAt; }

  public boolean isRevoked() { return revoked; }
  public void setRevoked(boolean revoked) { this.revoked = revoked; }

  public Instant getStepUpAt() { return stepUpAt; }
  public void setStepUpAt(Instant stepUpAt) { this.stepUpAt = stepUpAt; }

  public Instant getLastSeenAt() { return lastSeenAt; }
  public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

  public String getTokenHash() { return tokenHash; }
  public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

  public boolean isMfaSatisfied() { return mfaSatisfied; }
  public void setMfaSatisfied(boolean mfaSatisfied) { this.mfaSatisfied = mfaSatisfied; }

  public UUID getActiveWorkspaceId() { return activeWorkspaceId; }
  public void setActiveWorkspaceId(UUID activeWorkspaceId) { this.activeWorkspaceId = activeWorkspaceId; }

  public int getStepUpFailures() { return stepUpFailures; }
  public void setStepUpFailures(int stepUpFailures) { this.stepUpFailures = stepUpFailures; }

  @PrePersist
  void onCreate() {
    if (issuedAt == null) issuedAt = Instant.now();
  }
}
