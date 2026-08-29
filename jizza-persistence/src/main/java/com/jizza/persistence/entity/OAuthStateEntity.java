package com.jizza.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oauth_states")
public class OAuthStateEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private UUID connectionId;

  private UUID sessionId;

  @Column(nullable = false, unique = true)
  private String state;

  private byte[] codeVerifier;

  @Column(columnDefinition = "text")
  private String redirectUri;

  @Column(nullable = false)
  private Instant expiresAt;

  private boolean used;

  private Instant createdAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public UUID getConnectionId() { return connectionId; }
  public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }

  public UUID getSessionId() { return sessionId; }
  public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

  public String getState() { return state; }
  public void setState(String state) { this.state = state; }

  public byte[] getCodeVerifier() { return codeVerifier; }
  public void setCodeVerifier(byte[] codeVerifier) { this.codeVerifier = codeVerifier; }

  public String getRedirectUri() { return redirectUri; }
  public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }

  public Instant getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

  public boolean isUsed() { return used; }
  public void setUsed(boolean used) { this.used = used; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}
