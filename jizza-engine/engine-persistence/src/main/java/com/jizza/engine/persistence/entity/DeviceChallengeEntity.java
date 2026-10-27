package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A single-use sign-in challenge for a device. */
@Entity
@Table(name = "device_challenges")
public class DeviceChallengeEntity {

  @Id
  @Column(name = "nonce")
  private String id;

  @Column(nullable = false)
  private UUID deviceId;

  @Column(nullable = false)
  private Instant expiresAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }

  public UUID getDeviceId() { return deviceId; }
  public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }

  public Instant getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
