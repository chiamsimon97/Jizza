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

/** A device the user has signed in from, keyed by a hash of its user agent. */
@Entity
@Table(name = "user_devices")
public class UserDeviceEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false, columnDefinition = "text")
  private String fingerprint;

  @Column(columnDefinition = "text")
  private String label;

  @JdbcTypeCode(SqlTypes.INET)
  private InetAddress lastIp;

  private Instant firstSeenAt;

  private Instant lastSeenAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getFingerprint() { return fingerprint; }
  public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }

  public InetAddress getLastIp() { return lastIp; }
  public void setLastIp(InetAddress lastIp) { this.lastIp = lastIp; }

  public Instant getFirstSeenAt() { return firstSeenAt; }
  public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }

  public Instant getLastSeenAt() { return lastSeenAt; }
  public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (firstSeenAt == null) firstSeenAt = now;
    if (lastSeenAt == null) lastSeenAt = now;
  }
}
