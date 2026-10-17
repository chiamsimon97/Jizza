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

/** An event in a service, detected by polling an API for items not seen before. */
@Entity
@Table(name = "service_triggers")
public class ServiceTriggerEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID connectionId;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String label;

  @Column(columnDefinition = "text")
  private String description;

  @Column(nullable = false)
  private UUID apiId;

  private UUID resourceId;

  @Column(nullable = false)
  private String kind = "polling";

  private int pollIntervalMinutes = 15;

  private String itemsPath;

  @Column(nullable = false)
  private String idField = "id";

  private boolean enabled = true;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode seenIds;

  private Instant lastPolledAt;

  private Instant createdAt;

  private Instant updatedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getConnectionId() { return connectionId; }
  public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }

  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }

  public UUID getApiId() { return apiId; }
  public void setApiId(UUID apiId) { this.apiId = apiId; }

  public UUID getResourceId() { return resourceId; }
  public void setResourceId(UUID resourceId) { this.resourceId = resourceId; }

  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }

  public int getPollIntervalMinutes() { return pollIntervalMinutes; }
  public void setPollIntervalMinutes(int pollIntervalMinutes) { this.pollIntervalMinutes = pollIntervalMinutes; }

  public String getItemsPath() { return itemsPath; }
  public void setItemsPath(String itemsPath) { this.itemsPath = itemsPath; }

  public String getIdField() { return idField; }
  public void setIdField(String idField) { this.idField = idField; }

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }

  public JsonNode getSeenIds() { return seenIds; }
  public void setSeenIds(JsonNode seenIds) { this.seenIds = seenIds; }

  public Instant getLastPolledAt() { return lastPolledAt; }
  public void setLastPolledAt(Instant lastPolledAt) { this.lastPolledAt = lastPolledAt; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void onUpdate() { updatedAt = Instant.now(); }
}
