package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.MemoryReviewEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryReviewRepository extends JpaRepository<MemoryReviewEntity, UUID> {

  Optional<MemoryReviewEntity> findFirstByUserIdOrderByStartedAtDesc(UUID userId);

  Optional<MemoryReviewEntity> findFirstByUserIdAndTriggerOrderByStartedAtDesc(UUID userId,
                                                                               String trigger);
}
