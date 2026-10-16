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

  java.util.Optional<SessionEntity> findByTokenHash(String tokenHash);

  @Modifying
  @Query("update SessionEntity s set s.revoked = true where s.userId = :userId and s.id <> :keep")
  int revokeAllForUserExcept(@Param("userId") UUID userId, @Param("keep") UUID keep);

  /** Moves a person's sessions out of a workspace they no longer belong to. */
  @Modifying
  @Query("update SessionEntity s set s.activeWorkspaceId = :fallback "
      + "where s.userId = :userId and s.activeWorkspaceId = :workspaceId")
  int moveOffWorkspace(@Param("userId") UUID userId, @Param("workspaceId") UUID workspaceId,
                       @Param("fallback") UUID fallback);

  @Modifying
  @Query("delete from SessionEntity s where s.absoluteExpiresAt < :cutoff")
  int deleteExpired(@Param("cutoff") Instant cutoff);
}
