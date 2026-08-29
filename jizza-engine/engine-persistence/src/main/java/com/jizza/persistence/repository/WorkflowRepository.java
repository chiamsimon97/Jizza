package com.jizza.persistence.repository;

import com.jizza.common.enums.WorkflowStatus;
import com.jizza.persistence.entity.WorkflowEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository extends JpaRepository<WorkflowEntity, UUID> {
  List<WorkflowEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
  List<WorkflowEntity> findByUserIdAndStatus(UUID userId, WorkflowStatus status);
  List<WorkflowEntity> findBySourceTaskId(UUID sourceTaskId);
}
