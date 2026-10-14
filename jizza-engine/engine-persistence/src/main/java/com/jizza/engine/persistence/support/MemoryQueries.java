package com.jizza.engine.persistence.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads that hand memory to an agent. Both the user and the reader's domain scope are bound
 * into each {@code where} clause rather than applied afterwards, so a specialist can never receive
 * another domain's facts through this path -- the same rule the knowledge store follows (R22).
 *
 * <p>Every method returns ids with a score; callers load the rows they keep.
 */
@Component
public class MemoryQueries {

  /** Agent domains are short identifiers; anything else is refused before it reaches SQL. */
  private static final Pattern DOMAIN = Pattern.compile("[a-z0-9_-]{1,40}");

  private static final String VISIBLE = """
      (:allDomains or cardinality(%1$s.domains) = 0 or %1$s.domains && cast(:domains as text[]))
      """;

  @PersistenceContext
  private EntityManager em;

  /** Nearest active facts by cosine distance, embedded by {@code model}. */
  @Transactional(readOnly = true)
  public List<Scored> nearest(UUID userId, float[] embedding, String model, Collection<String> domains,
                              boolean allDomains, int k) {
    Query q = em.createNativeQuery("""
            select m.id, 1 - (m.embedding <=> cast(:vec as vector)) as similarity
              from memories m
             where m.user_id = :userId and m.status = 'active' and m.embedding is not null
               and m.embedding_model = :model
               and %s
             order by m.embedding <=> cast(:vec as vector)
             limit :k
            """.formatted(VISIBLE.formatted("m")))
        .setParameter("vec", Vectors.toLiteral(Vectors.fit(embedding)))
        .setParameter("userId", userId)
        .setParameter("model", model)
        .setParameter("k", k);
    return scored(scope(q, domains, allDomains));
  }

  /**
   * Active facts containing any of the terms. The fallback when a query has no useful vector, and
   * a second signal alongside it when it does.
   */
  @Transactional(readOnly = true)
  public List<Scored> lexical(UUID userId, Collection<String> terms, Collection<String> domains,
                              boolean allDomains, int k) {
    if (terms.isEmpty()) return List.of();
    StringJoiner patterns = new StringJoiner(",", "{", "}");
    for (String term : terms) patterns.add(arrayElement("%" + escapeLike(term) + "%"));
    Query q = em.createNativeQuery("""
            select m.id, 1.0 as similarity
              from memories m
             where m.user_id = :userId and m.status = 'active'
               and m.content ilike any (cast(:patterns as text[]))
               and %s
             order by m.updated_at desc
             limit :k
            """.formatted(VISIBLE.formatted("m")))
        .setParameter("userId", userId)
        .setParameter("patterns", patterns.toString())
        .setParameter("k", k);
    return scored(scope(q, domains, allDomains));
  }

  /** Active facts about any of the subjects. */
  @Transactional(readOnly = true)
  public List<Scored> aboutSubjects(UUID userId, Collection<UUID> subjectIds,
                                    Collection<String> domains, boolean allDomains, int k) {
    if (subjectIds.isEmpty()) return List.of();
    Query q = em.createNativeQuery("""
            select distinct m.id, 1.0 as similarity, m.updated_at
              from memories m
              join memory_about a on a.memory_id = m.id
             where m.user_id = :userId and m.status = 'active'
               and a.subject_id in (:subjects)
               and %s
             order by m.updated_at desc
             limit :k
            """.formatted(VISIBLE.formatted("m")))
        .setParameter("userId", userId)
        .setParameter("subjects", subjectIds)
        .setParameter("k", k);
    return scored(scope(q, domains, allDomains));
  }

  /** The user's most important active facts: the core profile every turn should know. */
  @Transactional(readOnly = true)
  public List<Scored> pinned(UUID userId, double minImportance, Collection<String> domains,
                             boolean allDomains, int k) {
    Query q = em.createNativeQuery("""
            select m.id, 1.0 as similarity
              from memories m
             where m.user_id = :userId and m.status = 'active' and m.importance >= :minImportance
               and %s
             order by m.importance desc, m.updated_at desc
             limit :k
            """.formatted(VISIBLE.formatted("m")))
        .setParameter("userId", userId)
        .setParameter("minImportance", minImportance)
        .setParameter("k", k);
    return scored(scope(q, domains, allDomains));
  }

  /** Subject ids the reader may see. */
  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public List<UUID> visibleSubjectIds(UUID userId, Collection<String> domains, boolean allDomains) {
    Query q = em.createNativeQuery("""
            select s.id from memory_subjects s
             where s.user_id = :userId and %s
            """.formatted(VISIBLE.formatted("s")))
        .setParameter("userId", userId);
    return ((List<Object>) scope(q, domains, allDomains).getResultList()).stream()
        .map(o -> (UUID) o).toList();
  }

  /** Records that these facts were handed to an agent; feeds recency and retention. */
  @Transactional
  public void touch(Collection<UUID> memoryIds, Instant at) {
    if (memoryIds.isEmpty()) return;
    em.createNativeQuery("""
            update memories set last_accessed_at = :at, access_count = access_count + 1
             where id in (:ids)
            """)
        .setParameter("at", at)
        .setParameter("ids", memoryIds)
        .executeUpdate();
  }

  private Query scope(Query q, Collection<String> domains, boolean allDomains) {
    return q.setParameter("allDomains", allDomains)
        .setParameter("domains", domainArray(domains));
  }

  static String domainArray(Collection<String> domains) {
    StringJoiner joiner = new StringJoiner(",", "{", "}");
    for (String d : domains) {
      String domain = d.toLowerCase(Locale.ROOT);
      if (!DOMAIN.matcher(domain).matches()) {
        throw new IllegalArgumentException("Not a valid agent domain: " + d);
      }
      joiner.add(domain);
    }
    return joiner.toString();
  }

  /** Quotes one element of a Postgres array literal. */
  private static String arrayElement(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String escapeLike(String term) {
    return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  @SuppressWarnings("unchecked")
  private static List<Scored> scored(Query q) {
    return ((List<Object[]>) q.getResultList()).stream()
        .map(r -> new Scored((UUID) r[0], ((Number) r[1]).doubleValue()))
        .toList();
  }

  /** @param similarity cosine similarity for vector hits; 1.0 for exact (structural) hits */
  public record Scored(UUID id, double similarity) {}
}
