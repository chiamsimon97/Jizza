package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An AI provider a person connected with their own key; see {@code V49__model_choice.sql}. */
@Entity
@Table(name = "llm_providers")
public class LlmProviderEntity {

  @Id
  private UUID id = UUID.randomUUID();

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String provider;

  @Column(columnDefinition = "text")
  private String baseUrl;

  /** The key, envelope-encrypted; never returned once saved. */
  private byte[] apiKey;

  /** The last four characters of the key, so the person can tell which one is saved. */
  private String keyHint;

  private boolean enabled = true;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode models;

  private Instant modelsRefreshedAt;

  @Column(columnDefinition = "text")
  private String lastError;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
    updatedAt = createdAt;
    if (models == null) models = com.jizza.engine.api.util.Json.mapper().createArrayNode();
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public void setId(UUID v) { this.id = v; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID v) { this.userId = v; }
  public String getProvider() { return provider; }
  public void setProvider(String v) { this.provider = v; }
  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String v) { this.baseUrl = v; }
  public byte[] getApiKey() { return apiKey; }
  public void setApiKey(byte[] v) { this.apiKey = v; }
  public String getKeyHint() { return keyHint; }
  public void setKeyHint(String v) { this.keyHint = v; }
  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean v) { this.enabled = v; }
  public JsonNode getModels() { return models; }
  public void setModels(JsonNode v) { this.models = v; }
  public Instant getModelsRefreshedAt() { return modelsRefreshedAt; }
  public void setModelsRefreshedAt(Instant v) { this.modelsRefreshedAt = v; }
  public String getLastError() { return lastError; }
  public void setLastError(String v) { this.lastError = v; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
