package com.jizza.engine.persistence.repository;

import com.jizza.engine.api.enums.TaskStatus;
import com.jizza.engine.persistence.entity.ScheduledTaskEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTaskEntity, UUID> {

  List<ScheduledTaskEntity> findByUserIdOrderByNextRunAtAsc(UUID userId);

  List<ScheduledTaskEntity> findByBoundWorkflowId(UUID workflowId);

  @Query("""
      select t from ScheduledTaskEntity t
      where t.status = :status and t.nextRunAt is not null and t.nextRunAt <= :now
      order by t.nextRunAt asc
      """)
  List<ScheduledTaskEntity> findDue(@Param("status") TaskStatus status, @Param("now") Instant now);

  List<ScheduledTaskEntity> findByStatus(TaskStatus status);
}
