package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.WorkspaceMemberEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMemberEntity, UUID> {
  List<WorkspaceMemberEntity> findByUserId(UUID userId);
  List<WorkspaceMemberEntity> findByWorkspaceIdOrderByJoinedAtAsc(UUID workspaceId);
  Optional<WorkspaceMemberEntity> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);
}
