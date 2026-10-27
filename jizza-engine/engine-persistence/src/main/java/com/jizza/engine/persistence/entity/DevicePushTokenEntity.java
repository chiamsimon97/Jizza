package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Where to push a phone to wake it; the token is encrypted. */
@Entity
@Table(name = "device_push_tokens")
public class DevicePushTokenEntity {

  @Id
  @Column(name = "device_id")
  private UUID id;

  @Column(nullable = false)
  private String provider;

  @Column(nullable = false)
  private byte[] token;

  @Column(nullable = false)
  private Instant updatedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public String getProvider() { return provider; }
  public void setProvider(String provider) { this.provider = provider; }

  public byte[] getToken() { return token; }
  public void setToken(byte[] token) { this.token = token; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
