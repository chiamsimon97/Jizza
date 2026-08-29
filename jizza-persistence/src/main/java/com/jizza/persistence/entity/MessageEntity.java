package com.jizza.persistence.entity;

import com.jizza.common.enums.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages")
public class MessageEntity {

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

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}
