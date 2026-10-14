package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import com.jizza.engine.api.enums.MemoryStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One remembered fact (table {@code memories}). */
@Entity
@Table(name = "memories")
public class MemoryEntryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private MemoryCategory category;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(columnDefinition = "text[]", nullable = false)
  private String[] domains = new String[0];

  @Column(nullable = false)
  private DataClassification classification = DataClassification.NONE;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  private String factKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode data;

  private float confidence = 1.0f;

  private float importance = 0.5f;

  @Column(nullable = false)
  private MemorySource source;

  private String sourceRef;

  private boolean confirmed;

  @Column(nullable = false)
  private MemoryStatus status = MemoryStatus.ACTIVE;

  private UUID supersededBy;

  @Column(nullable = false)
  private String contentHash;

  @JdbcTypeCode(SqlTypes.VECTOR)
  @Array(length = 1536)
  @Column(columnDefinition = "vector(1536)")
  private float[] embedding;

  private String embeddingModel;

  private Instant expiresAt;

  private Instant lastAccessedAt;

  private int accessCount;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "memory_about", joinColumns = @JoinColumn(name = "memory_id"))
  @Column(name = "subject_id")
  private Set<UUID> subjectIds = new LinkedHashSet<>();

  private Instant createdAt;

  private Instant updatedAt;

  @Version
  private long version;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public MemoryCategory getCategory() { return category; }
  public void setCategory(MemoryCategory category) { this.category = category; }

  public String[] getDomains() { return domains; }
  public void setDomains(String[] domains) { this.domains = domains == null ? new String[0] : domains; }

  public DataClassification getClassification() { return classification; }
  public void setClassification(DataClassification classification) { this.classification = classification; }

  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }

  public String getFactKey() { return factKey; }
  public void setFactKey(String factKey) { this.factKey = factKey; }

  public JsonNode getData() { return data; }
  public void setData(JsonNode data) { this.data = data; }

  public float getConfidence() { return confidence; }
  public void setConfidence(float confidence) { this.confidence = confidence; }

  public float getImportance() { return importance; }
  public void setImportance(float importance) { this.importance = importance; }

  public MemorySource getSource() { return source; }
  public void setSource(MemorySource source) { this.source = source; }

  public String getSourceRef() { return sourceRef; }
  public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }

  public boolean isConfirmed() { return confirmed; }
  public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }

  public MemoryStatus getStatus() { return status; }
  public void setStatus(MemoryStatus status) { this.status = status; }

  public UUID getSupersededBy() { return supersededBy; }
  public void setSupersededBy(UUID supersededBy) { this.supersededBy = supersededBy; }

  public String getContentHash() { return contentHash; }
  public void setContentHash(String contentHash) { this.contentHash = contentHash; }

  public float[] getEmbedding() { return embedding; }
  public void setEmbedding(float[] embedding) { this.embedding = embedding; }

  public String getEmbeddingModel() { return embeddingModel; }
  public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

  public Instant getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

  public Instant getLastAccessedAt() { return lastAccessedAt; }
  public void setLastAccessedAt(Instant lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

  public int getAccessCount() { return accessCount; }
  public void setAccessCount(int accessCount) { this.accessCount = accessCount; }

  public Set<UUID> getSubjectIds() { return subjectIds; }
  public void setSubjectIds(Set<UUID> subjectIds) {
    this.subjectIds = subjectIds == null ? new LinkedHashSet<>() : new LinkedHashSet<>(subjectIds);
  }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

  public long getVersion() { return version; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }
}
