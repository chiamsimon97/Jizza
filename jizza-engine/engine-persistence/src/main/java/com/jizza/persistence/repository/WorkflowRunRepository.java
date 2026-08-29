package com.jizza.persistence.repository;

import com.jizza.common.enums.RunStatus;
import com.jizza.persistence.entity.WorkflowRunEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {
  List<WorkflowRunEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
  List<WorkflowRunEntity> findByStatus(RunStatus status);
  List<WorkflowRunEntity> findByWorkflowIdAndStatus(UUID workflowId, RunStatus status);
}
