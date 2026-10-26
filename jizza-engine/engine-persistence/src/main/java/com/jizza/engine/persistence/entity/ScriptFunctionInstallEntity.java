package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A public function a person installed; see {@code V29__public_function_installs.sql}. */
@Entity
@Table(name = "script_function_installs")
public class ScriptFunctionInstallEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private UUID functionId;

  private Instant installedAt;

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID v) { this.userId = v; }
  public UUID getFunctionId() { return functionId; }
  public void setFunctionId(UUID v) { this.functionId = v; }
  public Instant getInstalledAt() { return installedAt; }

  @PrePersist
  void onCreate() {
    if (installedAt == null) installedAt = Instant.now();
  }
}
