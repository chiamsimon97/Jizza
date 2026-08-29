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
