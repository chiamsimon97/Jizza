package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** One of the server's protocol keys, encrypted with the master key. */
@Entity
@Table(name = "device_server_keys")
public class DeviceServerKeyEntity {

  @Id
  private String id;

  @Column(nullable = false)
  private byte[] privateKey;

  @Column(columnDefinition = "text")
  private String publicKey;

  @Column(nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }

  public byte[] getPrivateKey() { return privateKey; }
  public void setPrivateKey(byte[] privateKey) { this.privateKey = privateKey; }

  public String getPublicKey() { return publicKey; }
  public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
