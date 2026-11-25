package com.jizza.engine.persistence.entity.finance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A Kai default or regulatory figure (system row, no user) or one user's override of it. */
@Entity
@Table(name = "fin_config")
public class FinConfigEntity {

  @Id
  private UUID id = UUID.randomUUID();

  private UUID userId;

  @Column(name = "key", nullable = false)
  private String key;

  @Column(nullable = false)
  private String scope;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode value;

  @Column(nullable = false)
  private LocalDate effectiveFrom;

  private LocalDate effectiveTo;

  @Column(columnDefinition = "text")
  private String sourceUrl;

  private LocalDate verifiedOn;
  private String verifiedBy;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID v) { this.userId = v; }
  public String getKey() { return key; }
  public void setKey(String v) { this.key = v; }
  public String getScope() { return scope; }
  public void setScope(String v) { this.scope = v; }
  public JsonNode getValue() { return value; }
  public void setValue(JsonNode v) { this.value = v; }
  public LocalDate getEffectiveFrom() { return effectiveFrom; }
  public void setEffectiveFrom(LocalDate v) { this.effectiveFrom = v; }
  public LocalDate getEffectiveTo() { return effectiveTo; }
  public void setEffectiveTo(LocalDate v) { this.effectiveTo = v; }
  public String getSourceUrl() { return sourceUrl; }
  public void setSourceUrl(String v) { this.sourceUrl = v; }
  public LocalDate getVerifiedOn() { return verifiedOn; }
  public void setVerifiedOn(LocalDate v) { this.verifiedOn = v; }
  public String getVerifiedBy() { return verifiedBy; }
  public void setVerifiedBy(String v) { this.verifiedBy = v; }
  public Instant getUpdatedAt() { return updatedAt; }
}
