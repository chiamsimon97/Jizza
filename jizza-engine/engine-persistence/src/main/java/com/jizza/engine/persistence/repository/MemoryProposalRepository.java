package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.MemoryProposalEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemoryProposalRepository extends JpaRepository<MemoryProposalEntity, UUID> {

  Optional<MemoryProposalEntity> findByIdAndUserId(UUID id, UUID userId);

  List<MemoryProposalEntity> findByUserIdAndStatusInOrderByCreatedAtDesc(UUID userId,
                                                                        Collection<String> statuses,
                                                                        Pageable page);

  long countByUserIdAndStatus(UUID userId, String status);

  /** Whether the same suggestion is open, or was answered since {@code since}. */
  @Query("""
      select count(p) > 0 from MemoryProposalEntity p
      where p.userId = :userId and p.fingerprint = :fingerprint
        and (p.status = 'open' or p.createdAt >= :since)
      """)
  boolean seen(@Param("userId") UUID userId, @Param("fingerprint") String fingerprint,
               @Param("since") Instant since);

  /** Open suggestions nobody answered in time. */
  @Modifying
  @Query("""
      update MemoryProposalEntity p set p.status = 'expired', p.decidedAt = :now
      where p.status = 'open' and p.createdAt < :before
      """)
  int expireOpen(@Param("before") Instant before, @Param("now") Instant now);
}
