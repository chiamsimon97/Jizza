package com.jizza.engine.persistence.entity.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A person's finance container in their own account; see {@code V50__finance_foundation.sql}. */
@Entity
@Table(name = "fin_profile")
public class FinProfileEntity {

  @Id
  private UUID id = UUID.randomUUID();

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String baseCurrency = "INR";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode settings;

  @Version
  private long version;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
    updatedAt = createdAt;
    if (settings == null) settings = Json.obj();
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID v) { this.userId = v; }
  public String getBaseCurrency() { return baseCurrency; }
  public void setBaseCurrency(String v) { this.baseCurrency = v; }
  public JsonNode getSettings() { return settings; }
  public void setSettings(JsonNode v) { this.settings = v; }
  public long getVersion() { return version; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
