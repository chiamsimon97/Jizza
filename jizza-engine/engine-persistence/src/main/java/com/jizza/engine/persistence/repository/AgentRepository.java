package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.AgentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<AgentEntity, UUID> {
  List<AgentEntity> findByUserIdOrderByIsOrchestratorDescNameAsc(UUID userId);
  Optional<AgentEntity> findByUserIdAndKey(UUID userId, String key);
  Optional<AgentEntity> findByUserIdAndIsOrchestratorTrue(UUID userId);
  List<AgentEntity> findByUserIdAndIsOrchestratorFalseAndEnabledTrue(UUID userId);
  /** Direct reports, in the order a manager's roster lists them. */
  List<AgentEntity> findByParentAgentIdOrderByNameAsc(UUID parentAgentId);
  long countByUserIdAndIsCustomTrueAndStatus(UUID userId, String status);
  List<AgentEntity> findByOriginTemplateId(UUID originTemplateId);
}
