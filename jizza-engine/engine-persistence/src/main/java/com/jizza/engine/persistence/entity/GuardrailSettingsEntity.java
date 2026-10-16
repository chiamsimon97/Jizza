package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/** A user's delivery preferences for what guardrails send them: quiet hours and time zone. */
@Entity
@Table(name = "guardrail_settings")
public class GuardrailSettingsEntity {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  private LocalTime quietHoursStart;

  private LocalTime quietHoursEnd;

  private String timeZone;

  private Instant updatedAt;

  @PrePersist
  @PreUpdate
  void touch() {
    updatedAt = Instant.now();
  }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public LocalTime getQuietHoursStart() { return quietHoursStart; }
  public void setQuietHoursStart(LocalTime quietHoursStart) { this.quietHoursStart = quietHoursStart; }

  public LocalTime getQuietHoursEnd() { return quietHoursEnd; }
  public void setQuietHoursEnd(LocalTime quietHoursEnd) { this.quietHoursEnd = quietHoursEnd; }

  public String getTimeZone() { return timeZone; }
  public void setTimeZone(String timeZone) { this.timeZone = timeZone; }

  public Instant getUpdatedAt() { return updatedAt; }
}
