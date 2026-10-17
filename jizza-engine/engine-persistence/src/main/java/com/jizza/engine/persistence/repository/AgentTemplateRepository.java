package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.AgentTemplateEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTemplateRepository extends JpaRepository<AgentTemplateEntity, UUID> {
  List<AgentTemplateEntity> findByWorkspaceIdOrderByNameAsc(UUID workspaceId);
  List<AgentTemplateEntity> findByWorkspaceIdInAndStatusOrderByNameAsc(Collection<UUID> workspaceIds,
                                                                      String status);
}
