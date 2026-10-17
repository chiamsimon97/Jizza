package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inbound_events")
public class InboundEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID subscriptionId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode payload;

  private Boolean verified;

  private Instant receivedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getSubscriptionId() { return subscriptionId; }
  public void setSubscriptionId(UUID subscriptionId) { this.subscriptionId = subscriptionId; }

  public JsonNode getPayload() { return payload; }
  public void setPayload(JsonNode payload) { this.payload = payload; }

  public Boolean getVerified() { return verified; }
  public void setVerified(Boolean verified) { this.verified = verified; }

  public Instant getReceivedAt() { return receivedAt; }
  public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

  @PrePersist
  void onCreate() {
    if (receivedAt == null) receivedAt = Instant.now();
  }
}
