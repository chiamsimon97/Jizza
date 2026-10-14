package com.jizza.engine.memory.retrieval;

import com.jizza.engine.api.enums.MemoryStatus;
import com.jizza.engine.api.memory.MemoryScope;
import com.jizza.engine.knowledge.embedding.EmbeddingProvider;
import com.jizza.engine.memory.config.MemoryProperties;
import com.jizza.engine.persistence.entity.MemoryEntryEntity;
import com.jizza.engine.persistence.entity.MemorySubjectEntity;
import com.jizza.engine.persistence.entity.MemorySubjectRelationEntity;
import com.jizza.engine.persistence.repository.MemoryEntryRepository;
import com.jizza.engine.persistence.repository.MemorySubjectRelationRepository;
import com.jizza.engine.persistence.repository.MemorySubjectRepository;
import com.jizza.engine.persistence.support.MemoryQueries;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hybrid retrieval: three independent ways of finding a relevant fact, merged and ranked.
 *
 * <ol>
 *   <li><b>Structure.</b> The subjects the request names, and the ones related to them, bring in
 *       every fact about them -- the part that makes "an investment decision for my wife" find her
 *       plan even when no word in the request appears in it.</li>
 *   <li><b>Meaning.</b> pgvector nearest neighbours of the request's embedding.</li>
 *   <li><b>Keywords.</b> Facts containing the request's significant words; also the fallback when
 *       no vector is available.</li>
 * </ol>
 *
 * <p>Each candidate is scored as a weighted sum of its similarity, subject match, recency,
 * confidence and importance (weights in {@code jizza.memory.retrieval.weights}); facts under the
 * minimum score are dropped. Optionally the user's most important facts are pinned in regardless.
 * Every query is bound to the user and the reader's scope in SQL; the loaded rows are checked again
 * here so a future query change cannot widen what a reader sees.
 */
@Component
public class HybridSearch {

  private static final Logger log = LoggerFactory.getLogger(HybridSearch.class);

  private static final Set<String> STOPWORDS = Set.of(
      "about", "after", "also", "been", "before", "being", "could", "does", "doing", "from",
      "have", "having", "help", "into", "just", "like", "make", "more", "most", "need", "needs",
      "only", "other", "over", "please", "should", "some", "such", "than", "that", "their",
      "them", "then", "there", "these", "they", "this", "those", "very", "want", "what", "when",
      "where", "which", "while", "will", "with", "would", "your", "yours");

  private final MemoryQueries queries;
  private final MemoryEntryRepository memories;
  private final MemorySubjectRepository subjects;
  private final MemorySubjectRelationRepository relations;
  private final EmbeddingProvider embeddings;
  private final MemoryProperties props;

  public HybridSearch(MemoryQueries queries, MemoryEntryRepository memories,
                      MemorySubjectRepository subjects, MemorySubjectRelationRepository relations,
                      EmbeddingProvider embeddings, MemoryProperties props) {
    this.queries = queries;
    this.memories = memories;
    this.subjects = subjects;
    this.relations = relations;
    this.embeddings = embeddings;
    this.props = props;
  }

  /** A ranked fact and the signals that put it there. */
  public record Ranked(MemoryEntryEntity memory, double score, double similarity, double subject,
                       boolean pinned) {}

  /**
   * @param subjects every subject the reader may see, by id, for rendering
   */
  public record Result(List<Ranked> ranked, SubjectResolver.Resolution resolution,
                       Map<UUID, MemorySubjectEntity> subjects,
                       List<MemorySubjectRelationEntity> relations) {}

  private static final class Candidate {
    double similarity;
    double subject;
    boolean pinned;
  }

  @Transactional(readOnly = true)
  public Result rank(UUID userId, String query, MemoryScope scope, int limit, boolean includePinned) {
    MemoryProperties.Retrieval cfg = props.getRetrieval();
    MemoryProperties.Weights w = cfg.getWeights();
    int k = Math.max(limit, cfg.getCandidateLimit());

    Map<UUID, MemorySubjectEntity> visibleSubjects = visibleSubjects(userId, scope);
    List<MemorySubjectRelationEntity> edges = visibleSubjects.isEmpty() ? List.of()
        : relations.findByUserId(userId).stream()
            .filter(e -> visibleSubjects.containsKey(e.getFromSubjectId())
                && visibleSubjects.containsKey(e.getToSubjectId()))
            .toList();
    SubjectResolver.Resolution resolution = SubjectResolver.resolve(query, visibleSubjects.values(),
        edges, cfg.getRelationDepth());

    Map<UUID, Candidate> candidates = new LinkedHashMap<>();
    Function<UUID, Candidate> at = id -> candidates.computeIfAbsent(id, x -> new Candidate());

    // 1. Structure.
    for (var hit : queries.aboutSubjects(userId, resolution.direct(), scope.domains(), scope.allDomains(), k)) {
      at.apply(hit.id()).subject = 1.0;
    }
    for (var hit : queries.aboutSubjects(userId, resolution.related(), scope.domains(), scope.allDomains(), k)) {
      Candidate c = at.apply(hit.id());
      c.subject = Math.max(c.subject, w.getRelatedSubject());
    }

    // 2. Meaning.
    for (var hit : semantic(userId, query, scope, k)) {
      if (hit.similarity() < cfg.getMinSimilarity()) continue;
      Candidate c = at.apply(hit.id());
      c.similarity = Math.max(c.similarity, Math.min(1.0, hit.similarity()));
    }

    // 3. Keywords.
    for (var hit : queries.lexical(userId, terms(query), scope.domains(), scope.allDomains(), k)) {
      Candidate c = at.apply(hit.id());
      c.similarity = Math.max(c.similarity, w.getKeywordHit());
    }

    // The core profile.
    if (includePinned) {
      for (var hit : queries.pinned(userId, cfg.getPinnedImportance(), scope.domains(), scope.allDomains(),
          cfg.getPinnedLimit())) {
        at.apply(hit.id()).pinned = true;
      }
    }

    Instant now = Instant.now();
    double halfLifeDays = Math.max(1.0, cfg.getRecencyHalfLife().toHours() / 24.0);
    List<Ranked> ranked = new ArrayList<>();
    for (MemoryEntryEntity m : memories.findAllById(candidates.keySet())) {
      if (!m.getUserId().equals(userId) || m.getStatus() != MemoryStatus.ACTIVE) continue;
      if (!scope.sees(List.of(m.getDomains()))) continue;
      if (m.getExpiresAt() != null && m.getExpiresAt().isBefore(now)) continue;

      Candidate c = candidates.get(m.getId());
      double ageDays = Math.max(0, Duration.between(m.getUpdatedAt(), now).toHours() / 24.0);
      double recency = Math.pow(0.5, ageDays / halfLifeDays);
      double score = w.getSimilarity() * c.similarity
          + w.getSubject() * c.subject
          + w.getRecency() * recency
          + w.getConfidence() * m.getConfidence()
          + w.getImportance() * m.getImportance();
      if (score < cfg.getMinScore() && !c.pinned) continue;
      ranked.add(new Ranked(m, score, c.similarity, c.subject, c.pinned));
    }
    ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
    List<Ranked> top = ranked.size() > limit ? ranked.subList(0, limit) : ranked;
    return new Result(List.copyOf(top), resolution, visibleSubjects, edges);
  }

  private List<MemoryQueries.Scored> semantic(UUID userId, String query, MemoryScope scope, int k) {
    if (query == null || query.isBlank()) return List.of();
    try {
      return queries.nearest(userId, embeddings.embed(query), embeddings.id(), scope.domains(),
          scope.allDomains(), k);
    } catch (RuntimeException e) {
      // No pgvector, or the embedding endpoint is down: structure and keywords still answer.
      log.debug("Semantic memory search unavailable; using structure and keywords only", e);
      return List.of();
    }
  }

  private Map<UUID, MemorySubjectEntity> visibleSubjects(UUID userId, MemoryScope scope) {
    List<MemorySubjectEntity> all = subjects.findByUserIdOrderByNameAsc(userId);
    if (all.isEmpty()) return Map.of();
    Set<UUID> visible = scope.allDomains() ? null
        : new HashSet<>(queries.visibleSubjectIds(userId, scope.domains(), false));
    return all.stream()
        .filter(s -> visible == null || visible.contains(s.getId()))
        .collect(Collectors.toMap(MemorySubjectEntity::getId, s -> s, (a, b) -> a, LinkedHashMap::new));
  }

  /** The significant words of a request, for the keyword signal. */
  static List<String> terms(String query) {
    if (query == null) return List.of();
    Set<String> terms = new LinkedHashSet<>();
    for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
      if (token.length() >= 4 && !STOPWORDS.contains(token)) terms.add(token);
      if (terms.size() == 8) break;
    }
    return List.copyOf(terms);
  }
}
