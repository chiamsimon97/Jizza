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

/**
 * Someone whose money is recorded in this account: the user ("self") or a person they manage who
 * has no login. Enum columns are lowercase strings, checked in the migration.
 */
@Entity
@Table(name = "fin_person")
public class FinPersonEntity {

  @Id
  private UUID id = UUID.randomUUID();

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String displayName;

  @Column(nullable = false)
  private String kind;

  @Column(nullable = false)
  private String type = "individual";

  private LocalDate dob;
  private boolean isEarning;
  private boolean isDependent;

  /** Envelope-encrypted PAN; never returned. Not written before a later phase. */
  private byte[] panEnc;
  private String panLast4;

  @Column(nullable = false)
  private String status = "active";

  private UUID handedOverToUserId;
  private int sortOrder;

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
  public String getDisplayName() { return displayName; }
  public void setDisplayName(String v) { this.displayName = v; }
  public String getKind() { return kind; }
  public void setKind(String v) { this.kind = v; }
  public String getType() { return type; }
  public void setType(String v) { this.type = v; }
  public LocalDate getDob() { return dob; }
  public void setDob(LocalDate v) { this.dob = v; }
  public boolean isEarning() { return isEarning; }
  public void setEarning(boolean v) { this.isEarning = v; }
  public boolean isDependent() { return isDependent; }
  public void setDependent(boolean v) { this.isDependent = v; }
  public byte[] getPanEnc() { return panEnc; }
  public void setPanEnc(byte[] v) { this.panEnc = v; }
  public String getPanLast4() { return panLast4; }
  public void setPanLast4(String v) { this.panLast4 = v; }
  public String getStatus() { return status; }
  public void setStatus(String v) { this.status = v; }
  public UUID getHandedOverToUserId() { return handedOverToUserId; }
  public void setHandedOverToUserId(UUID v) { this.handedOverToUserId = v; }
  public int getSortOrder() { return sortOrder; }
  public void setSortOrder(int v) { this.sortOrder = v; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
