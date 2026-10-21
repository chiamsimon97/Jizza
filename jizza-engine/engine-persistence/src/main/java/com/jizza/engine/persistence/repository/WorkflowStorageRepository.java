package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.WorkflowStorageEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface WorkflowStorageRepository extends JpaRepository<WorkflowStorageEntity, UUID> {

  Optional<WorkflowStorageEntity> findByUserIdAndKey(UUID userId, String key);

  /** For a read-then-write (increment, append) that two runs could do at once. */
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from WorkflowStorageEntity s where s.userId = :userId and s.key = :key")
  Optional<WorkflowStorageEntity> lockByUserIdAndKey(UUID userId, String key);

  long countByUserId(UUID userId);
}
