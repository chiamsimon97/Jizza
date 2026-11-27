package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.MemorySettingsEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemorySettingsRepository extends JpaRepository<MemorySettingsEntity, UUID> {

  List<MemorySettingsEntity> findByRetentionDaysNotNull();

  /**
   * Marks the user as reviewed up to {@code now}, unless another review got there first (since
   * {@code notBefore}). Returns 1 when this caller may run the review.
   */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query("""
      update MemorySettingsEntity s set s.lastReviewedAt = :now
      where s.userId = :userId and s.reviewEnabled = true
        and (s.lastReviewedAt is null or s.lastReviewedAt < :notBefore)
      """)
  int claimReview(@org.springframework.data.repository.query.Param("userId") UUID userId,
                  @org.springframework.data.repository.query.Param("now") java.time.Instant now,
                  @org.springframework.data.repository.query.Param("notBefore") java.time.Instant notBefore);
}
