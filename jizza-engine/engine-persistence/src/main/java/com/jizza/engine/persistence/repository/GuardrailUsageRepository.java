package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.GuardrailUsageEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuardrailUsageRepository extends JpaRepository<GuardrailUsageEntity, UUID> {

  long countByPolicyIdAndCreatedAtAfter(UUID policyId, Instant since);

  @Query("""
      select coalesce(sum(u.amount), 0) from GuardrailUsageEntity u
      where u.policyId = :policyId and u.createdAt > :since
      """)
  BigDecimal spentSince(@Param("policyId") UUID policyId, @Param("since") Instant since);
}
