package com.jizza.engine.persistence.repository;

import com.jizza.engine.api.enums.MemoryStatus;
import com.jizza.engine.persistence.entity.MemoryEntryEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * User-facing and write-path access to memories. Every query names the user. Reads that serve an
 * agent go through {@link com.jizza.engine.persistence.support.MemoryQueries} instead, where the
 * agent's domain scope is part of the SQL.
 */
public interface MemoryEntryRepository extends JpaRepository<MemoryEntryEntity, UUID>,
    JpaSpecificationExecutor<MemoryEntryEntity> {

  Optional<MemoryEntryEntity> findByIdAndUserId(UUID id, UUID userId);

  List<MemoryEntryEntity> findByUserIdAndContentHashAndStatusIn(UUID userId, String contentHash,
                                                                 Collection<MemoryStatus> statuses);

  List<MemoryEntryEntity> findByUserIdAndFactKeyAndStatusIn(UUID userId, String factKey,
                                                            Collection<MemoryStatus> statuses);

  List<MemoryEntryEntity> findByUserIdAndStatusInOrderByUpdatedAtDesc(
      UUID userId, Collection<MemoryStatus> statuses, Pageable pageable);

  /** Rows whose vector came from another embedding provider (or none), to re-embed. */
  @Query("""
      select m from MemoryEntryEntity m
      where m.embeddingModel is null or m.embeddingModel <> :model
      order by m.updatedAt desc
      """)
  List<MemoryEntryEntity> findStaleEmbeddings(@Param("model") String model, Pageable pageable);

  @Modifying
  @Query("delete from MemoryEntryEntity m where m.expiresAt is not null and m.expiresAt < :now")
  int deleteExpired(@Param("now") Instant now);

  @Modifying
  @Query("""
      delete from MemoryEntryEntity m
      where m.status in :statuses and m.updatedAt < :cutoff
      """)
  int deleteInactiveOlderThan(@Param("statuses") Collection<MemoryStatus> statuses,
                              @Param("cutoff") Instant cutoff);

  /** The user's own retention window: facts neither used nor updated since the cutoff. */
  @Modifying
  @Query("""
      delete from MemoryEntryEntity m
      where m.userId = :userId and m.updatedAt < :cutoff
        and (m.lastAccessedAt is null or m.lastAccessedAt < :cutoff)
      """)
  int deleteUnusedSince(@Param("userId") UUID userId, @Param("cutoff") Instant cutoff);
}
