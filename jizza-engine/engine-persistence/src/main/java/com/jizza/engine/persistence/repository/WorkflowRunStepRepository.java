package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.WorkflowRunStepEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRunStepRepository extends JpaRepository<WorkflowRunStepEntity, UUID> {
  List<WorkflowRunStepEntity> findByRunIdOrderByStartedAtAsc(UUID runId);
  Optional<WorkflowRunStepEntity> findByRunIdAndNodeId(UUID runId, String nodeId);

  /** Rows of (run id, step status, count) for several runs, for the history list. */
  @Query("""
      select s.runId, s.status, count(s) from WorkflowRunStepEntity s
       where s.runId in :runIds group by s.runId, s.status
      """)
  List<Object[]> countByRunAndStatus(@Param("runIds") Collection<UUID> runIds);
}
