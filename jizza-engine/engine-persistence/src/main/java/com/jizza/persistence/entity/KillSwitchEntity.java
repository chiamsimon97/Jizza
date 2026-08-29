package com.jizza.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One row per user; the primary key <em>is</em> the user id, as the Contract declares. */
@Entity
@Table(name = "kill_switch")
public class KillSwitchEntity {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Column(nullable = false)
  private boolean autonomyPaused;

  private Instant updatedAt;

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public boolean isAutonomyPaused() { return autonomyPaused; }
  public void setAutonomyPaused(boolean autonomyPaused) { this.autonomyPaused = autonomyPaused; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

  @PrePersist
  @PreUpdate
  void touch() { updatedAt = Instant.now(); }
}
