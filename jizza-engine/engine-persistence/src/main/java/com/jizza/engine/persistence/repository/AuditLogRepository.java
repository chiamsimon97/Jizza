package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.AuditLogEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

  List<AuditLogEntity> findByUserIdOrderBySeqAsc(UUID userId);

  List<AuditLogEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  Optional<AuditLogEntity> findFirstByUserIdOrderBySeqDesc(UUID userId);

  List<AuditLogEntity> findByUserIdAndActionOrderBySeqDesc(UUID userId, String action, Pageable pageable);

  long countByUserId(UUID userId);

  /** One window of a user's log, newest first. */
  @Query("""
      select a from AuditLogEntity a
       where a.userId = :userId
         and a.createdAt >= :from and a.createdAt < :to
       order by a.createdAt desc, a.seq desc
      """)
  List<AuditLogEntity> findWindow(@Param("userId") UUID userId, @Param("from") Instant from,
                                  @Param("to") Instant to, Pageable pageable);
}
