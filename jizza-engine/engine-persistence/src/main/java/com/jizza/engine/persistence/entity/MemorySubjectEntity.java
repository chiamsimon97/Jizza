package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.SubjectKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Someone or something in the user's life that memories are grouped around. */
@Entity
@Table(name = "memory_subjects")
public class MemorySubjectEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private SubjectKind kind;

  @Column(nullable = false)
  private String name;

  private String relation;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(columnDefinition = "text[]", nullable = false)
  private String[] aliases = new String[0];

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode attributes;

  private UUID connectionId;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(columnDefinition = "text[]", nullable = false)
  private String[] domains = new String[0];

  private Instant createdAt;

  private Instant updatedAt;

  @Version
  private long version;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public SubjectKind getKind() { return kind; }
  public void setKind(SubjectKind kind) { this.kind = kind; }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getRelation() { return relation; }
  public void setRelation(String relation) { this.relation = relation; }

  public String[] getAliases() { return aliases; }
  public void setAliases(String[] aliases) { this.aliases = aliases == null ? new String[0] : aliases; }

  public JsonNode getAttributes() { return attributes; }
  public void setAttributes(JsonNode attributes) { this.attributes = attributes; }

  public UUID getConnectionId() { return connectionId; }
  public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }

  public String[] getDomains() { return domains; }
  public void setDomains(String[] domains) { this.domains = domains == null ? new String[0] : domains; }

  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }

  public long getVersion() { return version; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() { updatedAt = Instant.now(); }
}
