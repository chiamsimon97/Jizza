package com.jizza.engine.persistence.repository;

import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {
  List<WorkflowRunEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
  List<WorkflowRunEntity> findByStatus(RunStatus status);
  List<WorkflowRunEntity> findByWorkflowIdAndStatus(UUID workflowId, RunStatus status);

  /** Runs that started in {@code [from, to)}, newest first, without their context. */
  @Query("""
      select new com.jizza.engine.persistence.repository.WorkflowRunRow(
               r.id, r.workflowId, r.version, r.triggerSource, r.status, r.currentNode, r.error,
               r.createdAt, r.updatedAt)
        from WorkflowRunEntity r
       where r.userId = :userId and r.createdAt >= :from and r.createdAt < :to
       order by r.createdAt desc
      """)
  List<WorkflowRunRow> findRows(@Param("userId") UUID userId, @Param("from") Instant from,
                                @Param("to") Instant to, Pageable pageable);

  @Query("""
      select new com.jizza.engine.persistence.repository.WorkflowRunRow(
               r.id, r.workflowId, r.version, r.triggerSource, r.status, r.currentNode, r.error,
               r.createdAt, r.updatedAt)
        from WorkflowRunEntity r
       where r.id in :ids
      """)
  List<WorkflowRunRow> findRowsByIdIn(@Param("ids") Collection<UUID> ids);
}
