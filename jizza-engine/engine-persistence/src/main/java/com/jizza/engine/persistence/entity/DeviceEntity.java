package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A paired device; see {@code V30__devices.sql}. */
@Entity
@Table(name = "devices")
public class DeviceEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID workspaceId;

  private UUID ownerUserId;

  private UUID enrolledBy;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String platform;

  private String osVersion;

  private String appVersion;

  private Integer protocolVersion;

  @Column(nullable = false)
  private String status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode tags;

  private String keyStorage;

  private String hostname;

  private boolean autoUpdate = true;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode manifest;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode actions;

  private boolean shellEnabled;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode blockedActions;

  /** The owner's action type for each tool the device added itself; MONEY when not set. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode toolTypes;

  /** read, approve or full: what the Jizza app on the owner's phone may do. */
  @Column(nullable = false)
  private String phoneAccess = "approve";

  private Instant lastSeenAt;

  private String lastIp;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  private Instant revokedAt;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getWorkspaceId() { return workspaceId; }
  public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }

  public UUID getOwnerUserId() { return ownerUserId; }
  public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }

  public String getHostname() { return hostname; }
  public void setHostname(String hostname) { this.hostname = hostname; }

  public boolean isAutoUpdate() { return autoUpdate; }
  public void setAutoUpdate(boolean autoUpdate) { this.autoUpdate = autoUpdate; }

  public UUID getEnrolledBy() { return enrolledBy; }
  public void setEnrolledBy(UUID enrolledBy) { this.enrolledBy = enrolledBy; }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getPlatform() { return platform; }
  public void setPlatform(String platform) { this.platform = platform; }

  public String getOsVersion() { return osVersion; }
  public void setOsVersion(String osVersion) { this.osVersion = osVersion; }

  public String getAppVersion() { return appVersion; }
  public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

  public Integer getProtocolVersion() { return protocolVersion; }
  public void setProtocolVersion(Integer protocolVersion) { this.protocolVersion = protocolVersion; }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public JsonNode getTags() { return tags; }
  public void setTags(JsonNode tags) { this.tags = tags; }

  public String getKeyStorage() { return keyStorage; }
  public void setKeyStorage(String keyStorage) { this.keyStorage = keyStorage; }

  public JsonNode getManifest() { return manifest; }
  public void setManifest(JsonNode manifest) { this.manifest = manifest; }

  public JsonNode getActions() { return actions; }
  public void setActions(JsonNode actions) { this.actions = actions; }

  public boolean isShellEnabled() { return shellEnabled; }
  public void setShellEnabled(boolean shellEnabled) { this.shellEnabled = shellEnabled; }

  public JsonNode getBlockedActions() { return blockedActions; }
  public void setBlockedActions(JsonNode blockedActions) { this.blockedActions = blockedActions; }
  public String getPhoneAccess() { return phoneAccess; }
  public void setPhoneAccess(String phoneAccess) { this.phoneAccess = phoneAccess; }

  public JsonNode getToolTypes() { return toolTypes; }
  public void setToolTypes(JsonNode toolTypes) { this.toolTypes = toolTypes; }

  public Instant getLastSeenAt() { return lastSeenAt; }
  public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

  public String getLastIp() { return lastIp; }
  public void setLastIp(String lastIp) { this.lastIp = lastIp; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

  public Instant getRevokedAt() { return revokedAt; }
  public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
