package com.jizza.engine.persistence.repository;

import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {
  List<WorkflowRunEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
  List<WorkflowRunEntity> findByStatus(RunStatus status);
  List<WorkflowRunEntity> findByWorkflowIdAndStatus(UUID workflowId, RunStatus status);
}
