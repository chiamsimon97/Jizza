package com.jizza.persistence.repository;

import com.jizza.common.enums.CapabilityType;
import com.jizza.persistence.entity.AgentCapabilityEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentCapabilityRepository extends JpaRepository<AgentCapabilityEntity, UUID> {
  List<AgentCapabilityEntity> findByAgentId(UUID agentId);
  List<AgentCapabilityEntity> findByAgentIdAndCapabilityType(UUID agentId, CapabilityType type);
  Optional<AgentCapabilityEntity> findByAgentIdAndCapabilityTypeAndCapabilityRef(
      UUID agentId, CapabilityType type, UUID ref);
  void deleteByAgentIdAndCapabilityTypeAndCapabilityRef(UUID agentId, CapabilityType type, UUID ref);
}
