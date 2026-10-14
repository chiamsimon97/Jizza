package com.jizza.engine.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memory_settings")
public class MemorySettingsEntity {

  @Id
  private UUID userId;

  private boolean autoLearn = true;

  private Integer retentionDays;

  private Instant updatedAt;

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public boolean isAutoLearn() { return autoLearn; }
  public void setAutoLearn(boolean autoLearn) { this.autoLearn = autoLearn; }

  public Integer getRetentionDays() { return retentionDays; }
  public void setRetentionDays(Integer retentionDays) { this.retentionDays = retentionDays; }

  public Instant getUpdatedAt() { return updatedAt; }

  @PrePersist
  @PreUpdate
  void touch() { updatedAt = Instant.now(); }
}
