package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.JobEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobEventRepository extends JpaRepository<JobEventEntity, JobEventEntity.Key> {

  List<JobEventEntity> findByJobIdAndSeqGreaterThanOrderBySeqAsc(UUID jobId, int afterSeq);

  List<JobEventEntity> findByJobIdOrderBySeqAsc(UUID jobId);
}
