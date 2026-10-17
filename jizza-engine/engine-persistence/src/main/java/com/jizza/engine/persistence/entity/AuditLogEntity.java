package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  /** Database-assigned total order; the hash chain is verified in this order. */
  @Generated(event = EventType.INSERT)
  @Column(insertable = false, updatable = false)
  private Long seq;

  @Column(nullable = false)
  private ActorType actorType;

  private UUID actorId;

  @Column(nullable = false)
  private String action;

  private String target;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode detail;

  @Column(columnDefinition = "text")
  private String prevHash;

  @Column(columnDefinition = "text")
  private String hash;

  @Column(columnDefinition = "text")
  private String requestId;

  private Instant createdAt;

  public Long getSeq() { return seq; }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public ActorType getActorType() { return actorType; }
  public void setActorType(ActorType actorType) { this.actorType = actorType; }

  public UUID getActorId() { return actorId; }
  public void setActorId(UUID actorId) { this.actorId = actorId; }

  public String getAction() { return action; }
  public void setAction(String action) { this.action = action; }

  public String getTarget() { return target; }
  public void setTarget(String target) { this.target = target; }

  public JsonNode getDetail() { return detail; }
  public void setDetail(JsonNode detail) { this.detail = detail; }

  public String getPrevHash() { return prevHash; }
  public void setPrevHash(String prevHash) { this.prevHash = prevHash; }

  public String getHash() { return hash; }
  public void setHash(String hash) { this.hash = hash; }

  public String getRequestId() { return requestId; }
  public void setRequestId(String requestId) { this.requestId = requestId; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}
