package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.WorkflowRunStepEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunStepRepository extends JpaRepository<WorkflowRunStepEntity, UUID> {
  List<WorkflowRunStepEntity> findByRunIdOrderByStartedAtAsc(UUID runId);
  Optional<WorkflowRunStepEntity> findByRunIdAndNodeId(UUID runId, String nodeId);
}
