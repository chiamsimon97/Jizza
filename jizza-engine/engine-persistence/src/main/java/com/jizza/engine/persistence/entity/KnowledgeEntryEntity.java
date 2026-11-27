package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.DataClassification;
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
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "knowledge_entries")
public class KnowledgeEntryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private UUID agentId;

  @Column(nullable = false)
  private String title;

  @Column(columnDefinition = "text")
  private String content;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode data;

  private DataClassification classification;

  @JdbcTypeCode(SqlTypes.VECTOR)
  @Array(length = 1536)
  @Column(columnDefinition = "vector(1536)")
  private float[] embedding;

  /** Never handed to an agent, its owner included; shown to the user only after a fresh step-up. */
  private boolean restricted;

  /** {@code note} (the agent's own) or {@code lesson} (how-to the user approved). */
  private String kind = "note";

  /** A lesson's subject: {@code tool:<name>}, {@code site:<host>}, {@code workflow:<id>} or {@code general}. */
  private String aboutKey;

  private String aboutLabel;

  /** How many times what a lesson is about went wrong before it was learned. */
  private int occurrences;

  private int accessCount;

  private Instant lastAccessedAt;

  private Instant createdAt;

  private Instant updatedAt;

  @Version
  private long version;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public UUID getAgentId() { return agentId; }
  public void setAgentId(UUID agentId) { this.agentId = agentId; }

  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }

  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }

  public JsonNode getData() { return data; }
  public void setData(JsonNode data) { this.data = data; }

  public DataClassification getClassification() { return classification; }
  public void setClassification(DataClassification classification) { this.classification = classification; }

  public float[] getEmbedding() { return embedding; }
  public void setEmbedding(float[] embedding) { this.embedding = embedding; }

  public boolean isRestricted() { return restricted; }
  public void setRestricted(boolean restricted) { this.restricted = restricted; }

  public int getAccessCount() { return accessCount; }
  public void setAccessCount(int accessCount) { this.accessCount = accessCount; }

  public Instant getLastAccessedAt() { return lastAccessedAt; }
  public void setLastAccessedAt(Instant lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

  public long getVersion() { return version; }
  public void setVersion(long version) { this.version = version; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void onUpdate() { updatedAt = Instant.now(); }

  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }

  public boolean isLesson() { return "lesson".equals(kind); }

  public String getAboutKey() { return aboutKey; }
  public void setAboutKey(String aboutKey) { this.aboutKey = aboutKey; }

  public String getAboutLabel() { return aboutLabel; }
  public void setAboutLabel(String aboutLabel) { this.aboutLabel = aboutLabel; }

  public int getOccurrences() { return occurrences; }
  public void setOccurrences(int occurrences) { this.occurrences = occurrences; }
}
