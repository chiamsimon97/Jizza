package com.jizza.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "webhook_subscriptions")
public class WebhookSubscriptionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  private UUID connectionId;

  @Column(nullable = false, unique = true)
  private String endpointToken;

  private UUID workflowId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode verifyConfig;

  private Boolean enabled;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public UUID getConnectionId() { return connectionId; }
  public void setConnectionId(UUID connectionId) { this.connectionId = connectionId; }

  public String getEndpointToken() { return endpointToken; }
  public void setEndpointToken(String endpointToken) { this.endpointToken = endpointToken; }

  public UUID getWorkflowId() { return workflowId; }
  public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }

  public JsonNode getVerifyConfig() { return verifyConfig; }
  public void setVerifyConfig(JsonNode verifyConfig) { this.verifyConfig = verifyConfig; }

  public Boolean getEnabled() { return enabled; }
  public void setEnabled(Boolean enabled) { this.enabled = enabled; }

}
