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

/** A typed edge between two subjects ("Priya" --holds--> "Priya's brokerage account"). */
@Entity
@Table(name = "memory_subject_relations")
public class MemorySubjectRelationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private UUID fromSubjectId;

  @Column(nullable = false)
  private UUID toSubjectId;

  @Column(nullable = false)
  private String relation;

  private Instant createdAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public UUID getFromSubjectId() { return fromSubjectId; }
  public void setFromSubjectId(UUID fromSubjectId) { this.fromSubjectId = fromSubjectId; }

  public UUID getToSubjectId() { return toSubjectId; }
  public void setToSubjectId(UUID toSubjectId) { this.toSubjectId = toSubjectId; }

  public String getRelation() { return relation; }
  public void setRelation(String relation) { this.relation = relation; }

  public Instant getCreatedAt() { return createdAt; }

  @PrePersist
  void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
}
