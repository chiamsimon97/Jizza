package com.jizza.engine.persistence.entity.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A managed adult's consent to have their money recorded in this account. */
@Entity
@Table(name = "fin_consent")
// Kai sees only notes for money; Vita keeps its health notes in the same table (V73).
@org.hibernate.annotations.SQLRestriction("domain = 'finance'")
public class FinConsentEntity {

  @Id
  private UUID id = UUID.randomUUID();

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private UUID personId;

  @Column(nullable = false)
  private String scope;

  @Column(nullable = false)
  private Instant grantedAt;

  private Instant withdrawnAt;
  private String channel;
  private UUID evidenceFileId;
  private LocalDate purgeAfter;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
    updatedAt = createdAt;
    if (grantedAt == null) grantedAt = createdAt;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID v) { this.userId = v; }
  public UUID getPersonId() { return personId; }
  public void setPersonId(UUID v) { this.personId = v; }
  public String getScope() { return scope; }
  public void setScope(String v) { this.scope = v; }
  public Instant getGrantedAt() { return grantedAt; }
  public void setGrantedAt(Instant v) { this.grantedAt = v; }
  public Instant getWithdrawnAt() { return withdrawnAt; }
  public void setWithdrawnAt(Instant v) { this.withdrawnAt = v; }
  public String getChannel() { return channel; }
  public void setChannel(String v) { this.channel = v; }
  public UUID getEvidenceFileId() { return evidenceFileId; }
  public void setEvidenceFileId(UUID v) { this.evidenceFileId = v; }
  public LocalDate getPurgeAfter() { return purgeAfter; }
  public void setPurgeAfter(LocalDate v) { this.purgeAfter = v; }
  public Instant getCreatedAt() { return createdAt; }
}
