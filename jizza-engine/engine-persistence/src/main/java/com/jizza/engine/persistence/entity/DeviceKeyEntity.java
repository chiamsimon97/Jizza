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

/** One public key of a device; the current one has no retired time. */
@Entity
@Table(name = "device_keys")
public class DeviceKeyEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID deviceId;

  @Column(nullable = false, columnDefinition = "text")
  private String publicKey;

  private String keyStorage;

  @Column(nullable = false)
  private Instant createdAt;

  private Instant retiredAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getDeviceId() { return deviceId; }
  public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }

  public String getPublicKey() { return publicKey; }
  public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

  public String getKeyStorage() { return keyStorage; }
  public void setKeyStorage(String keyStorage) { this.keyStorage = keyStorage; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getRetiredAt() { return retiredAt; }
  public void setRetiredAt(Instant retiredAt) { this.retiredAt = retiredAt; }
}
