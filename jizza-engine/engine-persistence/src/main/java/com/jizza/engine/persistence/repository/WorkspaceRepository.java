package com.jizza.engine.persistence.repository;

import com.jizza.engine.api.enums.WorkspaceType;
import com.jizza.engine.persistence.entity.WorkspaceEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {
  Optional<WorkspaceEntity> findFirstByCreatedByUserIdAndType(UUID userId, WorkspaceType type);
}
