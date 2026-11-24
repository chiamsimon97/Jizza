package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.LlmUsageEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LlmUsageRepository extends JpaRepository<LlmUsageEntity, UUID> {

  @Query("""
      select coalesce(sum(u.costUnits), 0) from LlmUsageEntity u
       where u.userId = :userId and u.createdAt >= :since and u.paidBy = 'platform'
      """)
  java.math.BigDecimal spendSince(@Param("userId") UUID userId, @Param("since") Instant since);

  @Query("""
      select coalesce(sum(u.costUnits), 0) from LlmUsageEntity u
       where u.userId = :userId and u.purpose = :purpose and u.createdAt >= :since
      """)
  java.math.BigDecimal spendSinceForPurpose(@Param("userId") UUID userId,
                                            @Param("purpose") String purpose,
                                            @Param("since") Instant since);

  List<LlmUsageEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  @Query("""
      select u from LlmUsageEntity u
       where u.userId = :userId
         and u.createdAt >= :from and u.createdAt < :to
       order by u.createdAt desc
      """)
  List<LlmUsageEntity> findWindow(@Param("userId") UUID userId, @Param("from") Instant from,
                                  @Param("to") Instant to, Pageable pageable);

  List<LlmUsageEntity> findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
      UUID userId, Instant since);
}
