package com.jizza.persistence.entity;

import com.jizza.common.enums.CapabilityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "agent_capabilities")
public class AgentCapabilityEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID agentId;

  @Column(nullable = false)
  private CapabilityType capabilityType;

  @Column(nullable = false)
  private UUID capabilityRef;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getAgentId() { return agentId; }
  public void setAgentId(UUID agentId) { this.agentId = agentId; }

  public CapabilityType getCapabilityType() { return capabilityType; }
  public void setCapabilityType(CapabilityType capabilityType) { this.capabilityType = capabilityType; }

  public UUID getCapabilityRef() { return capabilityRef; }
  public void setCapabilityRef(UUID capabilityRef) { this.capabilityRef = capabilityRef; }

}
