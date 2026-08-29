package com.jizza.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "agents")
public class AgentEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(name = "key", nullable = false)
  private String key;

  @Column(nullable = false)
  private String name;

  private String domain;

  @Column(nullable = false, columnDefinition = "text")
  private String systemPrompt;

  private boolean isOrchestrator;

  private boolean enabled;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getKey() { return key; }
  public void setKey(String key) { this.key = key; }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getDomain() { return domain; }
  public void setDomain(String domain) { this.domain = domain; }

  public String getSystemPrompt() { return systemPrompt; }
  public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

  public boolean isOrchestrator() { return isOrchestrator; }
  public void setOrchestrator(boolean isOrchestrator) { this.isOrchestrator = isOrchestrator; }

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }

}
