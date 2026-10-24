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

/** A function the user keeps under Functions; see {@code V22__script_functions.sql}. */
@Entity
@Table(name = "script_functions")
public class ScriptFunctionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String name;

  @Column(columnDefinition = "text")
  private String description;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode inputs;

  @Column(nullable = false, columnDefinition = "text")
  private String script;

  @Column(nullable = false)
  private String returnType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode returnFields;

  private Instant createdAt;
  private Instant updatedAt;

  public UUID getId() { return id; }
  public void setId(UUID v) { this.id = v; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID v) { this.userId = v; }
  public String getName() { return name; }
  public void setName(String v) { this.name = v; }
  public String getDescription() { return description; }
  public void setDescription(String v) { this.description = v; }
  public JsonNode getInputs() { return inputs; }
  public void setInputs(JsonNode v) { this.inputs = v; }
  public String getScript() { return script; }
  public void setScript(String v) { this.script = v; }
  public String getReturnType() { return returnType; }
  public void setReturnType(String v) { this.returnType = v; }
  public JsonNode getReturnFields() { return returnFields; }
  public void setReturnFields(JsonNode v) { this.returnFields = v; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void onUpdate() { updatedAt = Instant.now(); }
}
