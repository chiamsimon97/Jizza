package com.jizza.persistence.entity;

import com.jizza.common.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(nullable = false, columnDefinition = "text")
  private String passwordHash;

  private byte[] mfaSecret;

  private boolean mfaEnabled;

  @Column(columnDefinition = "text")
  private String recoveryHash;

  @Column(nullable = false)
  private UserStatus status;

  private int failedLogins;

  private Instant lockedUntil;

  private Instant lastLoginAt;

  private Instant createdAt;

  private Instant updatedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }

  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

  public byte[] getMfaSecret() { return mfaSecret; }
  public void setMfaSecret(byte[] mfaSecret) { this.mfaSecret = mfaSecret; }

  public boolean isMfaEnabled() { return mfaEnabled; }
  public void setMfaEnabled(boolean mfaEnabled) { this.mfaEnabled = mfaEnabled; }

  public String getRecoveryHash() { return recoveryHash; }
  public void setRecoveryHash(String recoveryHash) { this.recoveryHash = recoveryHash; }

  public UserStatus getStatus() { return status; }
  public void setStatus(UserStatus status) { this.status = status; }

  public int getFailedLogins() { return failedLogins; }
  public void setFailedLogins(int failedLogins) { this.failedLogins = failedLogins; }

  public Instant getLockedUntil() { return lockedUntil; }
  public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }

  public Instant getLastLoginAt() { return lastLoginAt; }
  public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void onUpdate() { updatedAt = Instant.now(); }
}
