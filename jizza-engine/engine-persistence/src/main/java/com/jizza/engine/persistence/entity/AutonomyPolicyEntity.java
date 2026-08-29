package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.AutonomyRule;
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
@Table(name = "autonomy_policies")
public class AutonomyPolicyEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  private UUID agentId;

  private String toolRef;

  @Column(nullable = false)
  private AutonomyRule rule;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode limits;

  private int precedence;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public UUID getAgentId() { return agentId; }
  public void setAgentId(UUID agentId) { this.agentId = agentId; }

  public String getToolRef() { return toolRef; }
  public void setToolRef(String toolRef) { this.toolRef = toolRef; }

  public AutonomyRule getRule() { return rule; }
  public void setRule(AutonomyRule rule) { this.rule = rule; }

  public JsonNode getLimits() { return limits; }
  public void setLimits(JsonNode limits) { this.limits = limits; }

  public int getPrecedence() { return precedence; }
  public void setPrecedence(int precedence) { this.precedence = precedence; }

}
