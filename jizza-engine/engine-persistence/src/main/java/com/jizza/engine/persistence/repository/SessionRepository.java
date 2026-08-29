package com.jizza.engine.persistence.repository;

import com.jizza.engine.persistence.entity.SessionEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {

  List<SessionEntity> findByUserIdOrderByIssuedAtDesc(UUID userId);

  @Modifying
  @Query("update SessionEntity s set s.revoked = true where s.userId = :userId")
  int revokeAllForUser(@Param("userId") UUID userId);

  @Modifying
  @Query("delete from SessionEntity s where s.absoluteExpiresAt < :cutoff")
  int deleteExpired(@Param("cutoff") Instant cutoff);
}
