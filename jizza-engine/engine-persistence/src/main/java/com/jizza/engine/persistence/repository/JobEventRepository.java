package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.JobEventEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobEventRepository extends JpaRepository<JobEventEntity, JobEventEntity.Key> {

  List<JobEventEntity> findByJobIdAndSeqGreaterThanOrderBySeqAsc(UUID jobId, int afterSeq);

  List<JobEventEntity> findByJobIdOrderBySeqAsc(UUID jobId);

  /** The chat job that raised a permission request, if one did. */
  @Query(value = "select cast(e.job_id as varchar) from job_events e where e.type = 'approval.required' "
      + "and e.payload ->> 'notificationId' = :notificationId limit 1", nativeQuery = true)
  Optional<String> findJobRaising(@Param("notificationId") String notificationId);
}
