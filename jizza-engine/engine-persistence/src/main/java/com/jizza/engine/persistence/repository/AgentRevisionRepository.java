package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.AgentRevisionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRevisionRepository extends JpaRepository<AgentRevisionEntity, UUID> {
  List<AgentRevisionEntity> findByAgentIdOrderByRevisionDesc(UUID agentId);
  Optional<AgentRevisionEntity> findFirstByAgentIdOrderByRevisionDesc(UUID agentId);
  Optional<AgentRevisionEntity> findByAgentIdAndRevision(UUID agentId, int revision);
}
