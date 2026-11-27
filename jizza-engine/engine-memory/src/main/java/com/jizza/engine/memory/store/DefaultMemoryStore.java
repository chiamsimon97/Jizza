package com.jizza.engine.memory.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.cosmos.MemoryNode;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import com.jizza.engine.api.enums.MemoryStatus;
import com.jizza.engine.api.enums.MemoryWriteOutcome;
import com.jizza.engine.api.enums.SubjectKind;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.memory.Actor;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryChange;
import com.jizza.engine.api.memory.MemoryDraft;
import com.jizza.engine.api.memory.MemoryFilter;
import com.jizza.engine.api.memory.MemoryScope;
import com.jizza.engine.api.memory.MemorySettings;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.api.memory.MemorySubject;
import com.jizza.engine.api.memory.MemoryWrite;
import com.jizza.engine.api.memory.SubjectDraft;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.knowledge.embedding.EmbeddingProvider;
import com.jizza.engine.memory.config.MemoryProperties;
import com.jizza.engine.memory.config.MemoryScopes;
import com.jizza.engine.memory.retrieval.HybridSearch;
import com.jizza.engine.persistence.entity.MemoryEntryEntity;
import com.jizza.engine.persistence.entity.MemoryHistoryEntity;
import com.jizza.engine.persistence.entity.MemorySettingsEntity;
import com.jizza.engine.persistence.entity.MemorySubjectEntity;
import com.jizza.engine.persistence.entity.MemorySubjectRelationEntity;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.MemoryAccessRepository;
import com.jizza.engine.persistence.repository.MemoryEntryRepository;
import com.jizza.engine.persistence.repository.MemoryHistoryRepository;
import com.jizza.engine.persistence.repository.MemorySettingsRepository;
import com.jizza.engine.persistence.repository.MemorySubjectRelationRepository;
import com.jizza.engine.persistence.repository.MemorySubjectRepository;
import com.jizza.engine.persistence.support.MemoryEvents;
import com.jizza.engine.persistence.support.MemoryQueries;
import com.jizza.engine.persistence.support.Vectors;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The memory write path and the user-facing reads.
 *
 * <p>What it guarantees on every write:
 * <ul>
 *   <li><b>No copies.</b> The same words about the same subjects reinforce the existing fact; so
 *       does a fact whose meaning is near-identical ({@code duplicate-similarity}).</li>
 *   <li><b>One value per slot.</b> A fact with a {@code factKey} replaces the active fact with the
 *       same key and subjects when it is at least as authoritative -- confirmed beats unconfirmed,
 *       and between equals the newer wins. A less authoritative one waits as {@code pending} for the
 *       user rather than overwriting what they confirmed.</li>
 *   <li><b>History, not overwrite.</b> Every change keeps what the fact said before.</li>
 *   <li><b>Audit without content.</b> The audit log records that memory changed and how it is
 *       classified, never what it says (R13).</li>
 * </ul>
 */
@Service
public class DefaultMemoryStore implements MemoryStore {

  private static final Logger log = LoggerFactory.getLogger(DefaultMemoryStore.class);

  private static final Set<MemoryStatus> LIVE = EnumSet.of(MemoryStatus.ACTIVE, MemoryStatus.PENDING);

  private final MemoryEntryRepository memories;
  private final MemorySubjectRepository subjects;
  private final MemorySubjectRelationRepository relations;
  private final MemoryHistoryRepository history;
  private final MemorySettingsRepository settings;
  private final ConnectionRepository connections;
  private final MemoryQueries queries;
  private final HybridSearch search;
  private final EmbeddingProvider embeddings;
  private final MemoryScopes scopes;
  private final MemoryProperties props;
  private final AuditLog audit;
  private final MemoryAccessRepository access;
  private final MemoryEvents events;

  public DefaultMemoryStore(MemoryEntryRepository memories, MemorySubjectRepository subjects,
                            MemorySubjectRelationRepository relations,
                            MemoryHistoryRepository history, MemorySettingsRepository settings,
                            ConnectionRepository connections, MemoryQueries queries,
                            HybridSearch search, EmbeddingProvider embeddings, MemoryScopes scopes,
                            MemoryProperties props, AuditLog audit,
                            MemoryAccessRepository access, MemoryEvents events) {
    this.memories = memories;
    this.subjects = subjects;
    this.relations = relations;
    this.history = history;
    this.settings = settings;
    this.connections = connections;
    this.queries = queries;
    this.search = search;
    this.embeddings = embeddings;
    this.scopes = scopes;
    this.props = props;
    this.audit = audit;
    this.access = access;
    this.events = events;
  }

  // ── Facts ─────────────────────────────────────────────────────────────────────────────────────

  @Override
  @Transactional
  public MemoryWrite remember(UUID userId, MemoryDraft draft, Actor actor) {
    if (draft.category() == null) throw new ValidationException("A memory needs a category");
    // Incognito reads memory but never writes it, whoever asks.
    if (Activity.current().map(a -> "incognito".equals(a.origin())).orElse(false)) {
      throw new ValidationException("This is an incognito chat, so nothing is saved to memory");
    }
    // Standing instructions come only from what the user typed in a chat; never from an event,
    // a schedule, or anything an agent read.
    if (draft.category() == MemoryCategory.INSTRUCTION && actor.type() != ActorType.USER
        && !Activity.fromUser()) {
      throw new ValidationException("Only you can give Jizza a standing instruction");
    }
    String content = validContent(draft.content());
    Set<UUID> about = ownedSubjects(userId, draft.subjectIds());
    MemorySource source = draft.source() == null ? sourceFor(actor) : draft.source();
    boolean confirmed = draft.confirmed() != null ? draft.confirmed() : source == MemorySource.USER;
    String factKey = MemoryMapping.normaliseKey(draft.factKey());
    String hash = MemoryMapping.contentHash(content, about);

    // Exactly the same fact again: reinforce, don't copy.
    Optional<MemoryEntryEntity> exact = memories
        .findByUserIdAndContentHashAndStatusIn(userId, hash, LIVE).stream().findFirst();
    if (exact.isPresent()) {
      return new MemoryWrite(reinforce(exact.get(), confirmed, draft.confidence(), actor),
          MemoryWriteOutcome.DUPLICATE, null);
    }

    float[] vector = embed(content, about);

    // Same meaning in different words: also a duplicate, unless it sets a different value of a
    // keyed slot (then it is a conflict, handled below).
    Optional<MemoryEntryEntity> nearDuplicate = nearDuplicate(userId, vector, draft.category(), about,
        factKey);
    if (nearDuplicate.isPresent()) {
      return new MemoryWrite(reinforce(nearDuplicate.get(), confirmed, draft.confidence(), actor),
          MemoryWriteOutcome.DUPLICATE, null);
    }

    MemoryEntryEntity entity = new MemoryEntryEntity();
    entity.setUserId(userId);
    entity.setCategory(draft.category());
    entity.setDomains(draft.domains() == null
        ? MemoryMapping.normaliseDomains(scopes.defaultDomains(draft.category()))
        : MemoryMapping.normaliseDomains(draft.domains()));
    entity.setClassification(draft.classification() == null
        ? MemoryScopes.defaultClassification(draft.category()) : draft.classification());
    entity.setContent(content);
    entity.setFactKey(factKey);
    entity.setData(draft.data());
    entity.setConfidence((float) clamp(draft.confidence(), confirmed ? 1.0 : props.getWrite().getAgentConfidence()));
    entity.setImportance((float) clamp(draft.importance(), props.getWrite().getDefaultImportance()));
    entity.setSource(source);
    entity.setSourceRef(draft.sourceRef());
    entity.setConfirmed(confirmed);
    entity.setExpiresAt(draft.expiresAt());
    entity.setSubjectIds(about);
    entity.setContentHash(hash);
    setEmbedding(entity, vector);

    // One value per slot.
    List<MemoryEntryEntity> rivals = factKey == null ? List.of()
        : memories.findByUserIdAndFactKeyAndStatusIn(userId, factKey, EnumSet.of(MemoryStatus.ACTIVE))
            .stream().filter(r -> MemoryMapping.sameSubjects(r.getSubjectIds(), about)).toList();
    boolean outranked = rivals.stream().anyMatch(r -> r.isConfirmed() && !confirmed);
    entity.setStatus(outranked ? MemoryStatus.PENDING : MemoryStatus.ACTIVE);
    MemoryEntryEntity saved = memories.save(entity);
    record(saved, "created", null, actor, outranked ? "conflicts with a confirmed fact" : null);

    UUID affected = null;
    MemoryWriteOutcome outcome = MemoryWriteOutcome.CREATED;
    if (outranked) {
      affected = rivals.getFirst().getId();
      outcome = MemoryWriteOutcome.PENDING_CONFLICT;
    } else {
      for (MemoryEntryEntity rival : rivals) {
        supersede(rival, saved, actor);
        affected = rival.getId();
        outcome = MemoryWriteOutcome.SUPERSEDED_PREVIOUS;
      }
    }

    auditChange(saved, actor, "memory.created", outcome.name().toLowerCase(Locale.ROOT));
    announceSaved(saved, actor, outcome);
    return new MemoryWrite(MemoryMapping.toMemory(saved), outcome, affected);
  }

  @Override
  @Transactional
  public Memory correct(UUID userId, UUID memoryId, MemoryDraft changes, Actor actor) {
    MemoryEntryEntity entity = owned(userId, memoryId);
    String previousContent = entity.getContent();
    JsonNode previousData = entity.getData();

    if (changes.category() != null) entity.setCategory(changes.category());
    if (changes.content() != null) entity.setContent(validContent(changes.content()));
    if (changes.factKey() != null) entity.setFactKey(MemoryMapping.normaliseKey(changes.factKey()));
    if (changes.data() != null) entity.setData(changes.data());
    if (changes.classification() != null) entity.setClassification(changes.classification());
    if (changes.importance() != null) entity.setImportance((float) clamp(changes.importance(), entity.getImportance()));
    if (changes.domains() != null) entity.setDomains(MemoryMapping.normaliseDomains(changes.domains()));
    if (changes.expiresAt() != null) entity.setExpiresAt(changes.expiresAt());
    if (changes.subjectIds() != null) entity.setSubjectIds(ownedSubjects(userId, changes.subjectIds()));

    // The user saying what is true is the strongest evidence there is.
    entity.setConfirmed(true);
    entity.setConfidence(1.0f);
    entity.setContentHash(MemoryMapping.contentHash(entity.getContent(), entity.getSubjectIds()));
    setEmbedding(entity, embed(entity.getContent(), entity.getSubjectIds()));
    entity.setUpdatedAt(java.time.Instant.now());
    MemoryEntryEntity saved = memories.save(entity);

    record(saved, "updated", previousContent, previousData, actor, null);
    if (saved.getStatus() == MemoryStatus.ACTIVE) replaceRivals(saved, actor);
    auditChange(saved, actor, "memory.updated", null);
    return MemoryMapping.toMemory(saved);
  }

  @Override
  @Transactional
  public Memory confirm(UUID userId, UUID memoryId, Actor actor) {
    MemoryEntryEntity entity = owned(userId, memoryId);
    if (entity.getStatus() == MemoryStatus.SUPERSEDED || entity.getStatus() == MemoryStatus.RETIRED) {
      throw new ConflictException("That memory is no longer active; restore it first");
    }
    entity.setConfirmed(true);
    entity.setConfidence(1.0f);
    entity.setStatus(MemoryStatus.ACTIVE);
    entity.setUpdatedAt(java.time.Instant.now());
    MemoryEntryEntity saved = memories.save(entity);
    record(saved, "confirmed", null, actor, null);
    replaceRivals(saved, actor);
    auditChange(saved, actor, "memory.confirmed", null);
    return MemoryMapping.toMemory(saved);
  }

  @Override
  @Transactional
  public Memory retire(UUID userId, UUID memoryId, String reason, Actor actor) {
    MemoryEntryEntity entity = owned(userId, memoryId);
    entity.setStatus(MemoryStatus.RETIRED);
    entity.setUpdatedAt(java.time.Instant.now());
    MemoryEntryEntity saved = memories.save(entity);
    record(saved, "retired", null, actor, reason);
    auditChange(saved, actor, "memory.retired", null);
    return MemoryMapping.toMemory(saved);
  }

  @Override
  @Transactional
  public Memory restore(UUID userId, UUID memoryId, Actor actor) {
    MemoryEntryEntity entity = owned(userId, memoryId);
    entity.setStatus(MemoryStatus.ACTIVE);
    entity.setSupersededBy(null);
    entity.setUpdatedAt(java.time.Instant.now());
    MemoryEntryEntity saved = memories.save(entity);
    record(saved, "restored", null, actor, null);
    replaceRivals(saved, actor);
    auditChange(saved, actor, "memory.restored", null);
    return MemoryMapping.toMemory(saved);
  }

  @Override
  @Transactional
  public void forget(UUID userId, UUID memoryId, Actor actor) {
    MemoryEntryEntity entity = owned(userId, memoryId);
    auditChange(entity, actor, "memory.deleted", null);
    // History and subject links go with it (on delete cascade), and so do its read stamps:
    // forgetting means forgetting.
    access.deleteItem(MemoryNode.FACT, entity.getId());
    memories.delete(entity);
  }

  @Override
  @Transactional
  public Memory share(UUID userId, UUID memoryId, String domain, Actor actor) {
    MemoryEntryEntity entity = owned(userId, memoryId);
    String d = validDomain(domain);
    List<String> current = new ArrayList<>(List.of(entity.getDomains()));
    if (current.isEmpty()) {
      throw new ConflictException("Every agent can already see this memory");
    }
    if (current.contains(d)) return MemoryMapping.toMemory(entity);
    current.add(d);
    return changeDomains(entity, current, "shared", d, actor, "memory.shared");
  }

  @Override
  @Transactional
  public Memory unshare(UUID userId, UUID memoryId, String domain, Actor actor) {
    MemoryEntryEntity entity = owned(userId, memoryId);
    String d = validDomain(domain);
    List<String> current = new ArrayList<>(List.of(entity.getDomains()));
    if (!current.contains(d)) return MemoryMapping.toMemory(entity);
    if (current.size() == 1) {
      // No domains means every agent: taking the last one away would widen, not narrow.
      throw new ConflictException("This is the only agent that can see this memory; move or forget it instead");
    }
    current.remove(d);
    return changeDomains(entity, current, "unshared", d, actor, "memory.unshared");
  }

  @Override
  @Transactional
  public Memory setDomains(UUID userId, UUID memoryId, List<String> domains, Actor actor) {
    MemoryEntryEntity entity = owned(userId, memoryId);
    List<String> target = domains == null ? List.of() : domains.stream().map(this::validDomain).toList();
    String note = String.join(",", entity.getDomains()) + " -> " + String.join(",",
        MemoryMapping.normaliseDomains(target));
    return changeDomains(entity, target, "moved", note, actor, "memory.moved");
  }

  @Override
  @Transactional
  public Memory restrict(UUID userId, UUID memoryId, boolean restricted, Actor actor) {
    MemoryEntryEntity entity = owned(userId, memoryId);
    if (entity.isRestricted() == restricted) return MemoryMapping.toMemory(entity);
    entity.setRestricted(restricted);
    entity.setUpdatedAt(java.time.Instant.now());
    MemoryEntryEntity saved = memories.save(entity);
    record(saved, restricted ? "restricted" : "unrestricted", null, actor, null);
    auditChange(saved, actor, restricted ? "memory.restricted" : "memory.unrestricted", null);
    return MemoryMapping.toMemory(saved);
  }

  private Memory changeDomains(MemoryEntryEntity entity, List<String> domains, String change,
                               String note, Actor actor, String action) {
    entity.setDomains(MemoryMapping.normaliseDomains(domains));
    entity.setUpdatedAt(java.time.Instant.now());
    MemoryEntryEntity saved = memories.save(entity);
    record(saved, change, null, actor, note);
    auditChange(saved, actor, action, null);
    return MemoryMapping.toMemory(saved);
  }

  private String validDomain(String domain) {
    String d = domain == null ? "" : domain.strip().toLowerCase(Locale.ROOT);
    if (!d.matches("[a-z0-9_-]{1,40}")) throw new ValidationException("Not a valid agent domain: " + domain);
    return d;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Memory> get(UUID userId, UUID memoryId) {
    return memories.findByIdAndUserId(memoryId, userId).map(MemoryMapping::toMemory);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Memory> list(UUID userId, MemoryFilter filter) {
    int limit = Math.max(1, Math.min(filter.limit() <= 0 ? 200 : filter.limit(), 1000));
    Specification<MemoryEntryEntity> spec = (root, query, cb) -> {
      List<Predicate> where = new ArrayList<>();
      where.add(cb.equal(root.get("userId"), userId));
      if (filter.category() != null) where.add(cb.equal(root.get("category"), filter.category()));
      if (filter.status() != null) where.add(cb.equal(root.get("status"), filter.status()));
      if (filter.subjectId() != null) where.add(cb.isMember(filter.subjectId(), root.get("subjectIds")));
      return cb.and(where.toArray(Predicate[]::new));
    };
    return memories.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "updatedAt")))
        .stream().map(MemoryMapping::toMemory).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<MemoryChange> history(UUID userId, UUID memoryId) {
    owned(userId, memoryId);
    return history.findByMemoryIdOrderByCreatedAtAsc(memoryId).stream()
        .map(MemoryMapping::toChange).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<Memory> search(UUID userId, String query, MemoryScope scope, int limit) {
    return search.rank(userId, query, scope, Math.max(1, Math.min(limit, 50)), false).ranked().stream()
        .map(r -> MemoryMapping.toMemory(r.memory()).withScore(r.score()))
        .toList();
  }

  // ── Subjects ──────────────────────────────────────────────────────────────────────────────────

  @Override
  @Transactional
  public MemorySubject upsertSubject(UUID userId, SubjectDraft draft, Actor actor) {
    if (draft.name() == null || draft.name().isBlank()) {
      throw new ValidationException("A subject needs a name");
    }
    SubjectKind kind = draft.kind() == null ? SubjectKind.OTHER : draft.kind();
    MemorySubjectEntity entity = findSubject(userId, kind, draft.name())
        .orElseGet(() -> {
          MemorySubjectEntity created = new MemorySubjectEntity();
          created.setUserId(userId);
          created.setKind(kind);
          created.setName(draft.name().strip());
          return created;
        });
    boolean created = entity.getId() == null;
    apply(userId, entity, draft);
    MemorySubjectEntity saved = subjects.save(entity);
    audit.record(userId, actor.type(), actor.id(), created ? "memory.subject.created" : "memory.subject.updated",
        saved.getId().toString(), Json.obj().put("kind", saved.getKind().name().toLowerCase(Locale.ROOT)));
    events.changed(userId, MemoryEvents.BODY, null, null);
    return toSubject(saved);
  }

  @Override
  @Transactional
  public MemorySubject updateSubject(UUID userId, UUID subjectId, SubjectDraft draft, Actor actor) {
    MemorySubjectEntity entity = ownedSubject(userId, subjectId);
    if (draft.name() != null && !draft.name().isBlank()) entity.setName(draft.name().strip());
    if (draft.kind() != null) entity.setKind(draft.kind());
    apply(userId, entity, draft);
    MemorySubjectEntity saved = subjects.save(entity);
    audit.record(userId, actor.type(), actor.id(), "memory.subject.updated", saved.getId().toString(), null);
    events.changed(userId, MemoryEvents.BODY, null, null);
    return toSubject(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<MemorySubject> subjects(UUID userId, MemoryScope scope) {
    Set<UUID> visible = scope.allDomains() ? null
        : Set.copyOf(queries.visibleSubjectIds(userId, scope));
    Map<UUID, List<MemorySubject.Relation>> edges = relations.findByUserId(userId).stream()
        .collect(Collectors.groupingBy(MemorySubjectRelationEntity::getFromSubjectId,
            Collectors.mapping(r -> new MemorySubject.Relation(r.getId(), r.getToSubjectId(), r.getRelation()),
                Collectors.toList())));
    return subjects.findByUserIdOrderByNameAsc(userId).stream()
        .filter(s -> visible == null || visible.contains(s.getId()))
        .map(s -> MemoryMapping.toSubject(s, edges.getOrDefault(s.getId(), List.of()).stream()
            .filter(r -> visible == null || visible.contains(r.toSubjectId())).toList()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<MemorySubject> subject(UUID userId, UUID subjectId) {
    return subjects.findByIdAndUserId(subjectId, userId).map(this::toSubject);
  }

  @Override
  @Transactional
  public void deleteSubject(UUID userId, UUID subjectId, Actor actor) {
    MemorySubjectEntity entity = ownedSubject(userId, subjectId);
    audit.record(userId, actor.type(), actor.id(), "memory.subject.deleted", subjectId.toString(), null);
    events.changed(userId, MemoryEvents.BODY, null, null);
    subjects.delete(entity);
  }

  @Override
  @Transactional
  public MemorySubject.Relation relate(UUID userId, UUID fromSubjectId, UUID toSubjectId,
                                       String relation, Actor actor) {
    ownedSubject(userId, fromSubjectId);
    ownedSubject(userId, toSubjectId);
    if (fromSubjectId.equals(toSubjectId)) throw new ValidationException("A subject cannot relate to itself");
    String name = relation == null || relation.isBlank() ? "related_to"
        : relation.strip().toLowerCase(Locale.ROOT);
    MemorySubjectRelationEntity edge = relations
        .findByFromSubjectIdAndToSubjectIdAndRelation(fromSubjectId, toSubjectId, name)
        .orElseGet(() -> {
          MemorySubjectRelationEntity created = new MemorySubjectRelationEntity();
          created.setUserId(userId);
          created.setFromSubjectId(fromSubjectId);
          created.setToSubjectId(toSubjectId);
          created.setRelation(name);
          MemorySubjectRelationEntity saved = relations.save(created);
          audit.record(userId, actor.type(), actor.id(), "memory.subject.related",
              fromSubjectId + "->" + toSubjectId, Json.obj().put("relation", name));
          events.changed(userId, MemoryEvents.BODY, null, null);
          return saved;
        });
    return new MemorySubject.Relation(edge.getId(), edge.getToSubjectId(), edge.getRelation());
  }

  @Override
  @Transactional
  public void unrelate(UUID userId, UUID relationId, Actor actor) {
    MemorySubjectRelationEntity edge = relations.findByIdAndUserId(relationId, userId)
        .orElseThrow(() -> new NotFoundException("Relation", relationId));
    relations.delete(edge);
    audit.record(userId, actor.type(), actor.id(), "memory.subject.unrelated", relationId.toString(), null);
    events.changed(userId, MemoryEvents.BODY, null, null);
  }

  // ── Settings ──────────────────────────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public MemorySettings settings(UUID userId) {
    return settings.findById(userId)
        .map(s -> new MemorySettings(s.isAutoLearn(), s.getRetentionDays(), s.isReviewEnabled()))
        .orElse(MemorySettings.defaults());
  }

  @Override
  @Transactional
  public MemorySettings updateSettings(UUID userId, MemorySettings update) {
    if (update.retentionDays() != null && update.retentionDays() <= 0) {
      throw new ValidationException("Retention must be a positive number of days, or empty to keep");
    }
    MemorySettingsEntity entity = settings.findById(userId).orElseGet(() -> {
      MemorySettingsEntity created = new MemorySettingsEntity();
      created.setUserId(userId);
      return created;
    });
    entity.setAutoLearn(update.autoLearn());
    entity.setRetentionDays(update.retentionDays());
    if (update.review() != null) entity.setReviewEnabled(update.review());
    settings.save(entity);
    audit.record(userId, com.jizza.engine.api.enums.ActorType.USER, userId, "memory.settings.updated", null,
        Json.obj().put("autoLearn", update.autoLearn())
            .put("retentionDays", update.retentionDays() == null ? "keep" : update.retentionDays().toString())
            .put("review", entity.isReviewEnabled()));
    return new MemorySettings(entity.isAutoLearn(), entity.getRetentionDays(), entity.isReviewEnabled());
  }

  // ── Internals ─────────────────────────────────────────────────────────────────────────────────

  /** A fact stated again: more certain, and confirmed if the repeat is. */
  private Memory reinforce(MemoryEntryEntity existing, boolean confirmed, Double confidence, Actor actor) {
    float stated = (float) clamp(confidence, existing.getConfidence());
    existing.setConfidence(Math.max(existing.getConfidence(), stated));
    if (confirmed && !existing.isConfirmed()) existing.setConfirmed(true);
    existing.setUpdatedAt(java.time.Instant.now());
    MemoryEntryEntity saved = memories.save(existing);
    record(saved, "reinforced", null, actor, null);
    return MemoryMapping.toMemory(saved);
  }

  private Optional<MemoryEntryEntity> nearDuplicate(UUID userId, float[] vector, MemoryCategory category,
                                                    Set<UUID> about, String factKey) {
    if (vector == null) return Optional.empty();
    List<MemoryQueries.Scored> near;
    try {
      near = queries.nearest(userId, vector, embeddings.id(), MemoryScope.everything(), 5);
    } catch (RuntimeException e) {
      log.debug("Vector search unavailable for duplicate detection", e);
      return Optional.empty();
    }
    double threshold = props.getWrite().getDuplicateSimilarity();
    for (MemoryQueries.Scored hit : near) {
      if (hit.similarity() < threshold) continue;
      Optional<MemoryEntryEntity> candidate = memories.findById(hit.id())
          .filter(m -> m.getCategory() == category)
          .filter(m -> MemoryMapping.sameSubjects(m.getSubjectIds(), about))
          // Same slot, different words: possibly a changed value, so let conflict handling decide.
          .filter(m -> factKey == null || !factKey.equals(m.getFactKey()));
      if (candidate.isPresent()) return candidate;
    }
    return Optional.empty();
  }

  /** An active fact holds its slot alone: whatever else is active in it is superseded. */
  private void replaceRivals(MemoryEntryEntity winner, Actor actor) {
    if (winner.getFactKey() == null) return;
    memories.findByUserIdAndFactKeyAndStatusIn(winner.getUserId(), winner.getFactKey(),
            EnumSet.of(MemoryStatus.ACTIVE, MemoryStatus.PENDING)).stream()
        .filter(r -> !r.getId().equals(winner.getId()))
        .filter(r -> MemoryMapping.sameSubjects(r.getSubjectIds(), winner.getSubjectIds()))
        .forEach(r -> supersede(r, winner, actor));
  }

  private void supersede(MemoryEntryEntity old, MemoryEntryEntity replacement, Actor actor) {
    old.setStatus(MemoryStatus.SUPERSEDED);
    old.setSupersededBy(replacement.getId());
    old.setUpdatedAt(java.time.Instant.now());
    memories.save(old);
    record(old, "superseded", null, actor, "replaced by " + replacement.getId());
  }

  private void apply(UUID userId, MemorySubjectEntity entity, SubjectDraft draft) {
    if (draft.relation() != null) {
      entity.setRelation(draft.relation().isBlank() ? null : draft.relation().strip().toLowerCase(Locale.ROOT));
    }
    if (draft.aliases() != null) {
      Set<String> merged = new LinkedHashSet<>(List.of(entity.getAliases()));
      merged.addAll(List.of(MemoryMapping.normaliseAliases(draft.aliases())));
      merged.removeIf(a -> a.equalsIgnoreCase(entity.getName()));
      entity.setAliases(merged.toArray(String[]::new));
    }
    if (draft.attributes() != null) entity.setAttributes(mergeAttributes(entity.getAttributes(), draft.attributes()));
    if (draft.domains() != null) entity.setDomains(MemoryMapping.normaliseDomains(draft.domains()));
    if (draft.connectionId() != null) {
      connections.findById(draft.connectionId())
          .filter(c -> c.getUserId().equals(userId))
          .orElseThrow(() -> new NotFoundException("Connection", draft.connectionId()));
      entity.setConnectionId(draft.connectionId());
    }
  }

  private static JsonNode mergeAttributes(JsonNode current, JsonNode update) {
    if (current == null || !current.isObject() || !update.isObject()) return update;
    ObjectNode merged = current.deepCopy();
    merged.setAll((ObjectNode) update);
    return merged;
  }

  /** By name or alias, case-insensitively, within a kind. */
  private Optional<MemorySubjectEntity> findSubject(UUID userId, SubjectKind kind, String name) {
    String wanted = name.strip();
    return subjects.findByUserIdOrderByNameAsc(userId).stream()
        .filter(s -> s.getKind() == kind)
        .filter(s -> s.getName().equalsIgnoreCase(wanted)
            || List.of(s.getAliases()).stream().anyMatch(a -> a.equalsIgnoreCase(wanted)))
        .findFirst();
  }

  private MemorySubject toSubject(MemorySubjectEntity entity) {
    List<MemorySubject.Relation> edges = relations.findByFromSubjectIdIn(List.of(entity.getId())).stream()
        .map(r -> new MemorySubject.Relation(r.getId(), r.getToSubjectId(), r.getRelation()))
        .toList();
    return MemoryMapping.toSubject(entity, edges);
  }

  private Set<UUID> ownedSubjects(UUID userId, Collection<UUID> ids) {
    if (ids == null || ids.isEmpty()) return new LinkedHashSet<>();
    Set<UUID> wanted = new LinkedHashSet<>(ids);
    Set<UUID> found = subjects.findByUserIdAndIdIn(userId, wanted).stream()
        .map(MemorySubjectEntity::getId).collect(Collectors.toSet());
    for (UUID id : wanted) {
      // Another user's subject is reported exactly like a missing one.
      if (!found.contains(id)) throw new NotFoundException("Subject", id);
    }
    return wanted;
  }

  private MemoryEntryEntity owned(UUID userId, UUID memoryId) {
    return memories.findByIdAndUserId(memoryId, userId)
        .orElseThrow(() -> new NotFoundException("Memory", memoryId));
  }

  private MemorySubjectEntity ownedSubject(UUID userId, UUID subjectId) {
    return subjects.findByIdAndUserId(subjectId, userId)
        .orElseThrow(() -> new NotFoundException("Subject", subjectId));
  }

  private String validContent(String content) {
    String normalised = MemoryMapping.normaliseContent(content);
    if (normalised.isEmpty()) throw new ValidationException("A memory needs some content");
    int max = props.getWrite().getMaxContentChars();
    if (normalised.length() > max) {
      throw new ValidationException("A memory is one fact; keep it under " + max + " characters");
    }
    return normalised;
  }

  /** The text that is embedded: the fact plus who it is about, so "my wife" finds facts about her. */
  private float[] embed(String content, Collection<UUID> about) {
    StringBuilder text = new StringBuilder(content);
    if (!about.isEmpty()) {
      for (MemorySubjectEntity s : subjects.findAllById(about)) {
        text.append('\n').append(s.getName());
        if (s.getRelation() != null) text.append(" (").append(s.getRelation()).append(')');
      }
    }
    try {
      return embeddings.embed(text.toString());
    } catch (RuntimeException e) {
      // A hosted model being down must not lose what the user just said; the maintenance job
      // embeds the row later, and keyword and subject retrieval work without a vector.
      log.warn("Embedding failed; storing the memory without a vector for now: {}", e.getMessage());
      return null;
    }
  }

  private void setEmbedding(MemoryEntryEntity entity, float[] vector) {
    entity.setEmbedding(vector == null ? null : Vectors.fit(vector));
    entity.setEmbeddingModel(vector == null ? null : embeddings.id());
  }

  private void record(MemoryEntryEntity memory, String change, String previousContent, Actor actor, String note) {
    record(memory, change, previousContent, null, actor, note);
  }

  private void record(MemoryEntryEntity memory, String change, String previousContent,
                      JsonNode previousData, Actor actor, String note) {
    MemoryHistoryEntity entry = new MemoryHistoryEntity();
    entry.setMemoryId(memory.getId());
    entry.setUserId(memory.getUserId());
    entry.setChange(change);
    entry.setPreviousContent(previousContent);
    entry.setPreviousData(previousData);
    entry.setActorType(actor.type());
    entry.setActorId(actor.id());
    entry.setNote(note);
    entry.setContextRef(actor.contextRef());
    history.save(entry);
  }

  private void auditChange(MemoryEntryEntity memory, Actor actor, String action, String outcome) {
    ObjectNode detail = Json.obj()
        .put("category", memory.getCategory().name().toLowerCase(Locale.ROOT))
        .put("classification", String.valueOf(memory.getClassification()).toLowerCase(Locale.ROOT));
    if (outcome != null) detail.put("outcome", outcome);
    audit.record(memory.getUserId(), actor.type(), actor.id(), action, memory.getId().toString(), detail);
    String kind = switch (action) {
      case "memory.created" -> MemoryEvents.CREATED;
      case "memory.deleted" -> MemoryEvents.FORGOTTEN;
      case "memory.shared", "memory.unshared", "memory.moved", "memory.restricted",
           "memory.unrestricted" -> MemoryEvents.VISIBILITY;
      default -> MemoryEvents.UPDATED;
    };
    events.changed(memory.getUserId(), kind, MemoryNode.FACT, memory.getId());
  }

  /**
   * Tells the job that caused this write, if any, what it kept: the Memory panel's "Saved to
   * memory". Writes outside a job (the user editing memory, a scheduled import) announce nothing.
   */
  private static void announceSaved(MemoryEntryEntity saved, Actor actor, MemoryWriteOutcome outcome) {
    if (!Activity.active()) return;
    Activity.emit(ActivityTypes.MEMORY_SAVED, actor.type() == ActorType.AGENT ? actor.id() : null, null,
        p -> {
          p.put("id", saved.getId().toString()).put("text", saved.getContent())
              .put("category", saved.getCategory().name().toLowerCase(Locale.ROOT))
              .put("source", saved.getSource().name().toLowerCase(Locale.ROOT))
              .put("confirmed", saved.isConfirmed())
              .put("status", saved.getStatus().name().toLowerCase(Locale.ROOT))
              .put("outcome", outcome.name().toLowerCase(Locale.ROOT));
          if (saved.getSourceRef() != null) p.put("sourceRef", saved.getSourceRef());
        });
  }

  private static MemorySource sourceFor(Actor actor) {
    return switch (actor.type()) {
      case USER -> MemorySource.USER;
      case AGENT -> MemorySource.AGENT;
      case SYSTEM -> MemorySource.CONVERSATION;
    };
  }

  private static double clamp(Double value, double fallback) {
    double v = value == null ? fallback : value;
    return Math.max(0.0, Math.min(1.0, v));
  }
}
