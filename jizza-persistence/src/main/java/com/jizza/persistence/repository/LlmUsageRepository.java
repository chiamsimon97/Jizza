package com.jizza.persistence.repository;

import com.jizza.persistence.entity.LlmUsageEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LlmUsageRepository extends JpaRepository<LlmUsageEntity, UUID> {

  @Query("""
      select coalesce(sum(u.costUnits), 0) from LlmUsageEntity u
       where u.userId = :userId and u.createdAt >= :since
      """)
  java.math.BigDecimal spendSince(@Param("userId") UUID userId, @Param("since") Instant since);
}
