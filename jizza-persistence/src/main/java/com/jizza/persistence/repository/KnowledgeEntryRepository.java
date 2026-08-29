package com.jizza.persistence.repository;

import com.jizza.persistence.entity.KnowledgeEntryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntryEntity, UUID> {

  List<KnowledgeEntryEntity> findByAgentIdOrderByUpdatedAtDesc(UUID agentId, Pageable pageable);

  long countByAgentId(UUID agentId);

  /**
   * Lexical fallback used when no embedding is available for the query. Always scoped to a single
   * agent id — the namespace is part of the query, never a filter applied afterwards.
   */
  @Query("""
      select k from KnowledgeEntryEntity k
      where k.agentId = :agentId
        and (lower(k.title) like lower(concat('%', :q, '%'))
             or lower(k.content) like lower(concat('%', :q, '%')))
      order by k.updatedAt desc
      """)
  List<KnowledgeEntryEntity> searchLexical(@Param("agentId") UUID agentId, @Param("q") String q,
                                           Pageable pageable);

  void deleteByUserId(UUID userId);
}
