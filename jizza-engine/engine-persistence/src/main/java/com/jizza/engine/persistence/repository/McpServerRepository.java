package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.McpServerEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRepository extends JpaRepository<McpServerEntity, UUID> {
  List<McpServerEntity> findByWorkspaceIdOrderByNameAsc(UUID workspaceId);
  List<McpServerEntity> findByUserIdOrderByNameAsc(UUID userId);
  Optional<McpServerEntity> findByOauthState(String state);
  boolean existsByUserIdAndSlug(UUID userId, String slug);
}
