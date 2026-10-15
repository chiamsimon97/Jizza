package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.MessageRole;
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
@Table(name = "messages")
public class MessageEntity {

  public static final String STREAMING = "streaming";
  public static final String COMPLETE = "complete";
  public static final String FAILED = "failed";
  public static final String CANCELLED = "cancelled";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID conversationId;

  @Column(nullable = false)
  private MessageRole role;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  private Instant createdAt;

  @Column(nullable = false)
  private String status = COMPLETE;

  private UUID jobId;

  private UUID clientMessageId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode metadata;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getConversationId() { return conversationId; }
  public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

  public MessageRole getRole() { return role; }
  public void setRole(MessageRole role) { this.role = role; }

  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public UUID getJobId() { return jobId; }
  public void setJobId(UUID jobId) { this.jobId = jobId; }

  public UUID getClientMessageId() { return clientMessageId; }
  public void setClientMessageId(UUID clientMessageId) { this.clientMessageId = clientMessageId; }

  public JsonNode getMetadata() { return metadata; }
  public void setMetadata(JsonNode metadata) { this.metadata = metadata; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (metadata == null) metadata = com.jizza.engine.api.util.Json.obj();
  }
}
