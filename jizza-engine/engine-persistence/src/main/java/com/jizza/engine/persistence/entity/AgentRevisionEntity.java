package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One saved version of what a custom agent is told, so a change in behaviour can be traced. */
@Entity
@Table(name = "agent_revisions")
public class AgentRevisionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID agentId;

  @Column(nullable = false)
  private int revision;

  @Column(nullable = false)
  private String name;

  @Column(length = 300)
  private String description;

  @Column(nullable = false, columnDefinition = "text")
  private String systemPrompt;

  @Column(nullable = false)
  private String modelTier;

  private UUID changedByUserId;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getAgentId() { return agentId; }
  public void setAgentId(UUID agentId) { this.agentId = agentId; }

  public int getRevision() { return revision; }
  public void setRevision(int revision) { this.revision = revision; }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }

  public String getSystemPrompt() { return systemPrompt; }
  public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

  public String getModelTier() { return modelTier; }
  public void setModelTier(String modelTier) { this.modelTier = modelTier; }

  public UUID getChangedByUserId() { return changedByUserId; }
  public void setChangedByUserId(UUID changedByUserId) { this.changedByUserId = changedByUserId; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
