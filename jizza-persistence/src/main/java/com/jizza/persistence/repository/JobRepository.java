package com.jizza.persistence.repository;

import com.jizza.persistence.entity.JobEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<JobEntity, UUID> {

  /**
   * Lease a batch of ready jobs atomically. {@code for update skip locked} is what makes two worker
   * processes safe to run against the same queue.
   */
  @Query(value = """
      update job_queue
         set status = 'leased', leased_by = :worker, leased_until = :until,
             attempts = attempts + 1, updated_at = now()
       where id in (
         select id from job_queue
          where status = 'ready' and run_after <= now()
          order by run_after
          limit :batch
          for update skip locked)
      returning *
      """, nativeQuery = true)
  @Modifying
  List<JobEntity> leaseBatch(@Param("worker") String worker, @Param("until") Instant until,
                             @Param("batch") int batch);

  /** Jobs whose lease expired because the worker died mid-flight; safe to re-offer. */
  @Modifying
  @Query("""
      update JobEntity j set j.status = com.jizza.common.enums.JobStatus.READY, j.leasedBy = null,
                             j.leasedUntil = null
       where j.status = com.jizza.common.enums.JobStatus.LEASED and j.leasedUntil < :now
      """)
  int reclaimExpiredLeases(@Param("now") Instant now);

  List<JobEntity> findByStatus(com.jizza.common.enums.JobStatus status);
}
