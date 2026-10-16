package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.AutonomyPolicyEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutonomyPolicyRepository extends JpaRepository<AutonomyPolicyEntity, UUID> {
  List<AutonomyPolicyEntity> findByUserIdOrderByPrecedenceDesc(UUID userId);

  List<AutonomyPolicyEntity> findByWorkflowId(UUID workflowId);

  List<AutonomyPolicyEntity> findByWorkspaceId(UUID workspaceId);

  void deleteByUserId(UUID userId);

  /** Held while a budgeted call is counted, so two calls cannot both fit under the same budget. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from AutonomyPolicyEntity p where p.id = :id")
  Optional<AutonomyPolicyEntity> lockById(@Param("id") UUID id);
}
