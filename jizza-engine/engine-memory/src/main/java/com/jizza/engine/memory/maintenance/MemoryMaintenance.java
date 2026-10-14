package com.jizza.engine.memory.maintenance;

import com.jizza.engine.api.enums.MemoryStatus;
import com.jizza.engine.knowledge.embedding.EmbeddingProvider;
import com.jizza.engine.memory.config.MemoryProperties;
import com.jizza.engine.persistence.entity.MemoryEntryEntity;
import com.jizza.engine.persistence.entity.MemorySettingsEntity;
import com.jizza.engine.persistence.entity.MemorySubjectEntity;
import com.jizza.engine.persistence.repository.MemoryEntryRepository;
import com.jizza.engine.persistence.support.MemoryAccessWriter;
import com.jizza.engine.persistence.repository.MemorySettingsRepository;
import com.jizza.engine.persistence.repository.MemorySubjectRepository;
import com.jizza.engine.persistence.support.Vectors;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps memory honest over time (R48).
 *
 * <ul>
 *   <li>Facts past their {@code expires_at} are deleted.</li>
 *   <li>Superseded and retired facts are kept for {@code jizza.memory.retention.inactive} so the
 *       user can see what changed, then deleted.</li>
 *   <li>A user who set a retention window has facts that were neither used nor updated within it
 *       deleted.</li>
 *   <li>Facts embedded by a different model (or not yet embedded, because the provider was down)
 *       are re-embedded in batches, so switching providers never leaves search comparing
 *       incompatible vectors.</li>
 * </ul>
 */
@Service
public class MemoryMaintenance {

  private static final Logger log = LoggerFactory.getLogger(MemoryMaintenance.class);

  private final MemoryEntryRepository memories;
  private final MemorySubjectRepository subjects;
  private final MemorySettingsRepository settings;
  private final EmbeddingProvider embeddings;
  private final MemoryProperties props;
  private final MemoryAccessWriter accessWriter;

  public MemoryMaintenance(MemoryEntryRepository memories, MemorySubjectRepository subjects,
                           MemorySettingsRepository settings, EmbeddingProvider embeddings,
                           MemoryProperties props, MemoryAccessWriter accessWriter) {
    this.memories = memories;
    this.subjects = subjects;
    this.settings = settings;
    this.embeddings = embeddings;
    this.props = props;
    this.accessWriter = accessWriter;
  }

  @Scheduled(cron = "${jizza.memory.retention.cron:0 45 3 * * *}")
  @Transactional
  public int applyRetention() {
    Instant now = Instant.now();
    int removed = memories.deleteExpired(now);
    removed += memories.deleteInactiveOlderThan(EnumSet.of(MemoryStatus.SUPERSEDED, MemoryStatus.RETIRED),
        now.minus(props.getRetention().getInactive()));
    for (MemorySettingsEntity user : settings.findByRetentionDaysNotNull()) {
      removed += memories.deleteUnusedSince(user.getUserId(),
          now.minus(Duration.ofDays(user.getRetentionDays())));
    }
    // Bulk deletes skip the store, so their read stamps are swept here.
    if (removed > 0) accessWriter.deleteOrphans();
    if (removed > 0) log.info("Memory retention removed {} fact(s)", removed);
    return removed;
  }

  @Scheduled(fixedDelayString = "${jizza.memory.reembed-interval:PT10M}",
      initialDelayString = "${jizza.memory.reembed-initial-delay:PT1M}")
  @Transactional
  public int reembedStale() {
    String model = embeddings.id();
    List<MemoryEntryEntity> stale = memories.findStaleEmbeddings(model,
        PageRequest.of(0, props.getRetention().getReembedBatch()));
    int done = 0;
    for (MemoryEntryEntity m : stale) {
      try {
        StringBuilder text = new StringBuilder(m.getContent());
        for (MemorySubjectEntity s : subjects.findAllById(m.getSubjectIds())) {
          text.append('\n').append(s.getName());
          if (s.getRelation() != null) text.append(" (").append(s.getRelation()).append(')');
        }
        m.setEmbedding(Vectors.fit(embeddings.embed(text.toString())));
        m.setEmbeddingModel(model);
        memories.save(m);
        done++;
      } catch (RuntimeException e) {
        log.warn("Re-embedding stopped after {} fact(s): {}", done, e.getMessage());
        break;
      }
    }
    if (done > 0) log.info("Re-embedded {} memory fact(s) with {}", done, model);
    return done;
  }
}
