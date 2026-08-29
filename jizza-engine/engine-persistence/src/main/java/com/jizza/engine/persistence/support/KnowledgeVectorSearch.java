package com.jizza.engine.persistence.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * pgvector similarity search. The agent id is bound into the {@code where} clause, not applied as a
 * post-filter, so a namespace can never leak through this path (R22).
 */
@Component
public class KnowledgeVectorSearch {

  @PersistenceContext
  private EntityManager em;

  /** @return entry ids ordered by cosine distance, nearest first. */
  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<ScoredId> nearest(UUID agentId, float[] embedding, int k) {
    var rows = em.createNativeQuery("""
            select id, (embedding <=> cast(:vec as vector)) as distance
              from knowledge_entries
             where agent_id = :agentId and embedding is not null
             order by embedding <=> cast(:vec as vector)
             limit :k
            """)
        .setParameter("vec", Vectors.toLiteral(Vectors.fit(embedding)))
        .setParameter("agentId", agentId)
        .setParameter("k", k)
        .getResultList();
    return ((List<Object[]>) rows).stream()
        .map(r -> new ScoredId((UUID) r[0], ((Number) r[1]).doubleValue()))
        .toList();
  }

  public record ScoredId(UUID id, double distance) {}
}
