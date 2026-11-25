package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

  /** Created by the user rather than seeded with the account. */
  private boolean isCustom;

  /** "When to use me": what its manager reads when choosing who to hand work to. */
  @Column(length = 300)
  private String description;

  /** draft, active or archived. Draft and archived agents are always disabled. */
  @Column(nullable = false)
  private String status = "active";

  @Column(nullable = false)
  private String modelTier = "standard";

  /** Built-in memory domains this agent may read besides its own. */
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(columnDefinition = "text[]", nullable = false)
  private String[] memoryDomains = new String[0];

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode avatar;

  private BigDecimal monthlyCostLimit;

  /** The agent this one reports to; null only for Jizza. */
  private UUID parentAgentId;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(columnDefinition = "text[]", nullable = false)
  private String[] responsibilities = new String[0];

  @Column(columnDefinition = "text")
  private String handsOffTo;

  private boolean memoryFromManager = true;

  /** May search the web and read pages with the model provider's own tools. */
  private boolean webAccess = true;

  /** The model it answers with by default (a model reference); null uses its tier. */
  private String model;

  private UUID originTemplateId;

  private Integer originTemplateVersion;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false)
  private Instant updatedAt = Instant.now();

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

  public boolean isCustom() { return isCustom; }
  public void setCustom(boolean custom) { this.isCustom = custom; }

  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public boolean isActive() { return "active".equals(status); }

  public String getModelTier() { return modelTier; }
  public void setModelTier(String modelTier) { this.modelTier = modelTier; }

  public String[] getMemoryDomains() { return memoryDomains; }
  public void setMemoryDomains(String[] memoryDomains) {
    this.memoryDomains = memoryDomains == null ? new String[0] : memoryDomains;
  }

  public JsonNode getAvatar() { return avatar; }
  public void setAvatar(JsonNode avatar) { this.avatar = avatar; }

  public BigDecimal getMonthlyCostLimit() { return monthlyCostLimit; }
  public void setMonthlyCostLimit(BigDecimal monthlyCostLimit) { this.monthlyCostLimit = monthlyCostLimit; }

  public UUID getParentAgentId() { return parentAgentId; }
  public void setParentAgentId(UUID parentAgentId) { this.parentAgentId = parentAgentId; }

  public String[] getResponsibilities() { return responsibilities; }
  public void setResponsibilities(String[] responsibilities) {
    this.responsibilities = responsibilities == null ? new String[0] : responsibilities;
  }

  public String getHandsOffTo() { return handsOffTo; }
  public void setHandsOffTo(String handsOffTo) { this.handsOffTo = handsOffTo; }

  public boolean isWebAccess() { return webAccess; }
  public void setWebAccess(boolean webAccess) { this.webAccess = webAccess; }

  public boolean isMemoryFromManager() { return memoryFromManager; }
  public void setMemoryFromManager(boolean memoryFromManager) { this.memoryFromManager = memoryFromManager; }

  public UUID getOriginTemplateId() { return originTemplateId; }
  public void setOriginTemplateId(UUID originTemplateId) { this.originTemplateId = originTemplateId; }

  public Integer getOriginTemplateVersion() { return originTemplateVersion; }
  public void setOriginTemplateVersion(Integer v) { this.originTemplateVersion = v; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

  public String getModel() { return model; }
  public void setModel(String v) { this.model = v; }
}
