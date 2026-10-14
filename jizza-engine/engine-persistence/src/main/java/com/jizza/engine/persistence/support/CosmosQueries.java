package com.jizza.engine.persistence.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The thin rows the Memory Cosmos places items with: ids, who can see them and what they are
 * about, never content. One query per store, scoped by user; at the scale Jizza targets (tens of
 * thousands of items) this is a few milliseconds, and it keeps placement rules in one place in Java
 * rather than split between SQL and code.
 */
@Component
public class CosmosQueries {

  @PersistenceContext
  private EntityManager em;

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<FactRef> facts(UUID userId, boolean includeInactive) {
    List<Object[]> rows = em.createNativeQuery("""
            select m.id, array_to_string(m.domains, ','),
                   string_agg(a.subject_id::text, ',' order by a.subject_id::text),
                   m.created_at, m.updated_at, m.restricted
              from memories m
              left join memory_about a on a.memory_id = m.id
             where m.user_id = :userId
               and (:inactive or m.status in ('active', 'pending'))
             group by m.id
            """)
        .setParameter("userId", userId)
        .setParameter("inactive", includeInactive)
        .getResultList();
    List<FactRef> out = new ArrayList<>(rows.size());
    for (Object[] r : rows) {
      out.add(new FactRef((UUID) r[0], split((String) r[1]),
          split((String) r[2]).stream().map(UUID::fromString).toList(),
          instant(r[3]), instant(r[4]), Boolean.TRUE.equals(r[5])));
    }
    return out;
  }

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<NoteRef> notes(UUID userId) {
    List<Object[]> rows = em.createNativeQuery("""
            select k.id, k.agent_id,
                   (select f.id from files f where f.knowledge_entry_id = k.id limit 1),
                   k.created_at, k.updated_at
              from knowledge_entries k
             where k.user_id = :userId
            """)
        .setParameter("userId", userId)
        .getResultList();
    List<NoteRef> out = new ArrayList<>(rows.size());
    for (Object[] r : rows) {
      out.add(new NoteRef((UUID) r[0], (UUID) r[1], (UUID) r[2], instant(r[3]), instant(r[4])));
    }
    return out;
  }

  private static List<String> split(String joined) {
    return joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split(","));
  }

  private static Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof Instant i) return i;
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    if (value instanceof java.time.OffsetDateTime o) return o.toInstant();
    return Instant.parse(value.toString());
  }

  /** @param subjectIds sorted, so the first is a stable choice of home */
  public record FactRef(UUID id, List<String> domains, List<UUID> subjectIds, Instant createdAt,
                        Instant updatedAt, boolean restricted) {}

  public record NoteRef(UUID id, UUID agentId, UUID fileId, Instant createdAt, Instant updatedAt) {}
}
