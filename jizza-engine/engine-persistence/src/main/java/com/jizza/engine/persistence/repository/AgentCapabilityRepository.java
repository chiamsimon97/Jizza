package com.jizza.engine.persistence.repository;

import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.persistence.entity.AgentCapabilityEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentCapabilityRepository extends JpaRepository<AgentCapabilityEntity, UUID> {
  List<AgentCapabilityEntity> findByAgentId(UUID agentId);

  List<AgentCapabilityEntity> findByCapabilityTypeAndCapabilityRef(CapabilityType type, UUID ref);
  List<AgentCapabilityEntity> findByAgentIdAndCapabilityType(UUID agentId, CapabilityType type);
  Optional<AgentCapabilityEntity> findByAgentIdAndCapabilityTypeAndCapabilityRef(
      UUID agentId, CapabilityType type, UUID ref);
  void deleteByAgentIdAndCapabilityTypeAndCapabilityRef(UUID agentId, CapabilityType type, UUID ref);
}
