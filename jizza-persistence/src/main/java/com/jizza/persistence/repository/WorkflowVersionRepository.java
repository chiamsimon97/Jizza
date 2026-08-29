package com.jizza.persistence.repository;

import com.jizza.persistence.entity.WorkflowVersionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersionEntity, UUID> {
  Optional<WorkflowVersionEntity> findByWorkflowIdAndVersion(UUID workflowId, int version);
  List<WorkflowVersionEntity> findByWorkflowIdOrderByVersionAsc(UUID workflowId);
}
