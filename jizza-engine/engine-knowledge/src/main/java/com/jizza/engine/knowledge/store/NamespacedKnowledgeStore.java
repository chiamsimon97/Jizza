package com.jizza.engine.knowledge.store;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.cosmos.MemoryAccessRecorder;
import com.jizza.engine.api.cosmos.MemoryNode;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.error.IsolationViolationException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.knowledge.KnowledgeStore;
import com.jizza.engine.api.memory.Actor;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.knowledge.embedding.EmbeddingProvider;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.KnowledgeEntryEntity;
import com.jizza.engine.persistence.entity.KnowledgeHistoryEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.FileRepository;
import com.jizza.engine.persistence.repository.KnowledgeEntryRepository;
import com.jizza.engine.persistence.repository.KnowledgeHistoryRepository;
import com.jizza.engine.persistence.repository.MemoryAccessRepository;
import com.jizza.engine.persistence.support.MemoryEvents;
import com.jizza.engine.persistence.support.KnowledgeVectorSearch;
import com.jizza.engine.persistence.support.Vectors;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-agent knowledge with isolation enforced here, in code, rather than left to prompting (R22).
 *
 * <p>The rule is structural: the calling agent id is the namespace, every query is scoped by it in
 * the {@code where} clause, and any entry that arrives claiming a different {@code agentId} is
 * rejected outright. There is no code path through this class that reads or writes another agent's
 * namespace, except {@link #move}, which only the user can call and which takes the note out of
 * the old namespace entirely.
 *
 * <p>A restricted entry is invisible to every agent, its owner included: {@code search} and
 * {@code get} skip it in the query, and an upsert naming it is refused as if it did not exist.
 *
 * <p>A lesson is an entry of kind {@code lesson}: how-to the user approved for this agent. It is
 * read like a note but written only through {@link #teach}; an agent's upsert naming one is
 * refused, so nothing an agent read (a web page, a connector response) can rewrite what the user
 * approved.
 */
@Service
public class NamespacedKnowledgeStore implements KnowledgeStore {

  private static final Logger log = LoggerFactory.getLogger(NamespacedKnowledgeStore.class);

  static final String NOTE = "note";
  static final String LESSON = "lesson";
  /** A lesson is one piece of advice, not a document. */
  static final int MAX_LESSON_CHARS = 600;
  private static final java.util.regex.Pattern ABOUT_KEY = java.util.regex.Pattern.compile(
      "general|tool:[A-Za-z0-9_.:-]{1,120}|site:[a-z0-9.-]{1,253}|workflow:[0-9a-f-]{36}");

  private final KnowledgeEntryRepository entries;
  private final AgentRepository agents;
  private final EmbeddingProvider embeddings;
  private final KnowledgeVectorSearch vectorSearch;
  private final AuditLog audit;
  private final KnowledgeHistoryRepository history;
  private final FileRepository files;
  private final MemoryAccessRepository access;
  private final MemoryEvents events;
  private final ObjectProvider<MemoryAccessRecorder> recorder;

  public NamespacedKnowledgeStore(KnowledgeEntryRepository entries, AgentRepository agents,
                                  EmbeddingProvider embeddings, KnowledgeVectorSearch vectorSearch,
                                  AuditLog audit, KnowledgeHistoryRepository history,
                                  FileRepository files, MemoryAccessRepository access,
                                  MemoryEvents events, ObjectProvider<MemoryAccessRecorder> recorder) {
    this.entries = entries;
    this.agents = agents;
    this.embeddings = embeddings;
    this.vectorSearch = vectorSearch;
    this.audit = audit;
    this.history = history;
    this.files = files;
    this.access = access;
    this.events = events;
    this.recorder = recorder;
  }

  @Override
  @Transactional(readOnly = true)
  public List<KnowledgeEntry> search(UUID agentId, String query, int k) {
    int limit = Math.max(1, Math.min(k, 50));
    Map<UUID, KnowledgeEntry> found = new LinkedHashMap<>();

    for (var scored : semanticIds(agentId, query, limit)) {
      entries.findById(scored.id())
          .filter(e -> e.getAgentId().equals(agentId) && !e.isRestricted())
          .ifPresent(e -> found.put(e.getId(), toDto(e).withScore(1.0 - scored.distance())));
    }
    if (found.size() < limit) {
      for (var entity : entries.searchLexical(agentId, query == null ? "" : query,
          PageRequest.of(0, limit))) {
        found.putIfAbsent(entity.getId(), toDto(entity));
      }
    }
    List<KnowledgeEntry> result = new ArrayList<>(found.values()).subList(0, Math.min(found.size(), limit));
    stampReads(agentId, result);
    return result;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<KnowledgeEntry> get(UUID agentId, UUID entryId) {
    Optional<KnowledgeEntry> entry = entries.findById(entryId)
        .map(entity -> {
          requireOwnNamespace(agentId, entity.getAgentId());
          return entity;
        })
        .filter(entity -> !entity.isRestricted())
        .map(this::toDto);
    entry.ifPresent(e -> stampReads(agentId, List.of(e)));
    return entry;
  }

  @Override
  @Transactional
  public KnowledgeEntry upsert(UUID agentId, KnowledgeEntry entry) {
    // An entry that names someone else's namespace is a bug or an attack; either way it stops here.
    if (entry.agentId() != null) requireOwnNamespace(agentId, entry.agentId());

    AgentEntity agent = agents.findById(agentId)
        .orElseThrow(() -> new NotFoundException("Agent", agentId));

    KnowledgeEntryEntity entity;
    if (entry.id() != null) {
      entity = entries.findById(entry.id())
          .orElseThrow(() -> new NotFoundException("Knowledge entry", entry.id()));
      requireOwnNamespace(agentId, entity.getAgentId());
      // The user put it out of reach; to the agent it does not exist.
      if (entity.isRestricted()) throw new NotFoundException("Knowledge entry", entry.id());
      if (entity.isLesson()) {
        throw new com.jizza.engine.api.error.ValidationException(
            "That is a lesson the user approved; only they can change it. Save a note of your own instead.");
      }
      record(entity, "updated", Actor.agent(agentId));
    } else {
      entity = new KnowledgeEntryEntity();
      entity.setAgentId(agentId);
      entity.setUserId(agent.getUserId());
    }

    entity.setTitle(entry.title());
    entity.setContent(entry.content());
    entity.setData(entry.data());
    entity.setClassification(entry.classification() == null
        ? DataClassification.NONE : entry.classification());
    entity.setUpdatedAt(Instant.now());
    entity.setEmbedding(Vectors.fit(embeddings.embed(embeddingText(entry))));

    KnowledgeEntryEntity saved = entries.save(entity);
    if (entry.id() == null) recordCreated(saved, Actor.agent(agentId));

    // The audit trail records that knowledge changed and how it is classified, never its content:
    // health and financial detail does not belong in a log (R13).
    audit.record(agent.getUserId(), ActorType.AGENT, agentId, "knowledge.upsert", saved.getTitle(),
        Json.obj().put("entryId", saved.getId().toString())
            .put("classification", String.valueOf(saved.getClassification())));
    events.changed(saved.getUserId(), entry.id() == null ? MemoryEvents.CREATED : MemoryEvents.UPDATED,
        MemoryNode.NOTE, saved.getId());
    return toDto(saved);
  }

  @Override
  @Transactional
  public void delete(UUID agentId, UUID entryId) {
    entries.findById(entryId).ifPresent(entity -> {
      requireOwnNamespace(agentId, entity.getAgentId());
      access.deleteItem(MemoryNode.NOTE, entity.getId());
      entries.delete(entity);
      audit.record(entity.getUserId(), ActorType.AGENT, agentId, "knowledge.delete",
          entity.getTitle(), null);
      events.changed(entity.getUserId(), MemoryEvents.FORGOTTEN, MemoryNode.NOTE, entity.getId());
    });
  }

  @Override
  @Transactional(readOnly = true)
  public List<KnowledgeEntry> list(UUID agentId, int limit) {
    return entries.findByAgentIdOrderByUpdatedAtDesc(agentId,
            PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
        .stream().map(this::toDto).toList();
  }

  @Override
  @Transactional
  public KnowledgeEntry edit(UUID agentId, UUID entryId, String title, String content, Actor actor) {
    KnowledgeEntryEntity entity = ownEntry(agentId, entryId);
    if (title == null && content == null) return toDto(entity);
    record(entity, "updated", actor);
    if (title != null && !title.isBlank()) entity.setTitle(title.strip());
    if (content != null) entity.setContent(content);
    entity.setUpdatedAt(Instant.now());
    entity.setEmbedding(Vectors.fit(embeddings.embed(embeddingText(toDto(entity)))));
    KnowledgeEntryEntity saved = entries.save(entity);
    audit.record(saved.getUserId(), actor.type(), actor.id(), "knowledge.updated",
        saved.getId().toString(), classificationOf(saved));
    events.changed(saved.getUserId(), MemoryEvents.UPDATED, MemoryNode.NOTE, saved.getId());
    return toDto(saved);
  }

  @Override
  @Transactional
  public KnowledgeEntry move(UUID fromAgentId, UUID entryId, UUID toAgentId, Actor actor) {
    KnowledgeEntryEntity entity = ownEntry(fromAgentId, entryId);
    AgentEntity target = agents.findById(toAgentId)
        .filter(a -> a.getUserId().equals(entity.getUserId()))
        .orElseThrow(() -> new NotFoundException("Agent", toAgentId));
    if (target.getId().equals(fromAgentId)) return toDto(entity);

    record(entity, "moved", actor);
    entity.setAgentId(target.getId());
    entity.setUpdatedAt(Instant.now());
    KnowledgeEntryEntity saved = entries.save(entity);
    // The file the note was read from belongs to the same namespace; it moves with it.
    files.findFirstByKnowledgeEntryId(entryId).ifPresent(file -> {
      file.setAgentId(target.getId());
      files.save(file);
    });
    audit.record(saved.getUserId(), actor.type(), actor.id(), "knowledge.moved", saved.getId().toString(),
        classificationOf(saved).put("from", fromAgentId.toString()).put("to", toAgentId.toString()));
    events.changed(saved.getUserId(), MemoryEvents.VISIBILITY, MemoryNode.NOTE, saved.getId());
    return toDto(saved);
  }

  @Override
  @Transactional
  public KnowledgeEntry restrict(UUID agentId, UUID entryId, boolean restricted, Actor actor) {
    KnowledgeEntryEntity entity = ownEntry(agentId, entryId);
    if (entity.isRestricted() == restricted) return toDto(entity);
    record(entity, restricted ? "restricted" : "unrestricted", actor);
    entity.setRestricted(restricted);
    KnowledgeEntryEntity saved = entries.save(entity);
    audit.record(saved.getUserId(), actor.type(), actor.id(),
        restricted ? "knowledge.restricted" : "knowledge.unrestricted", saved.getId().toString(),
        classificationOf(saved));
    events.changed(saved.getUserId(), MemoryEvents.VISIBILITY, MemoryNode.NOTE, saved.getId());
    return toDto(saved);
  }

  @Override
  @Transactional
  public KnowledgeEntry teach(UUID agentId, KnowledgeEntry.Lesson about, String content, Actor actor) {
    AgentEntity agent = agents.findById(agentId)
        .orElseThrow(() -> new NotFoundException("Agent", agentId));
    if (actor.type() == ActorType.AGENT) {
      throw new com.jizza.engine.api.error.ValidationException("Only the user can teach an agent a lesson");
    }
    String text = content == null ? "" : content.strip().replaceAll("\\s+", " ");
    if (text.isEmpty()) throw new com.jizza.engine.api.error.ValidationException("A lesson needs some advice");
    if (text.length() > MAX_LESSON_CHARS) {
      throw new com.jizza.engine.api.error.ValidationException(
          "A lesson is one piece of advice; keep it under " + MAX_LESSON_CHARS + " characters");
    }
    String key = about == null || about.aboutKey() == null ? "general" : about.aboutKey().strip();
    if (!ABOUT_KEY.matcher(key).matches()) {
      throw new com.jizza.engine.api.error.ValidationException("A lesson is about a tool, a site, a workflow, or nothing in particular");
    }
    String label = about == null || about.aboutLabel() == null || about.aboutLabel().isBlank()
        ? null : clip(about.aboutLabel().strip(), 120);
    int occurrences = about == null ? 0 : Math.max(0, about.occurrences());

    // The same advice about the same thing again: the lesson grows stronger, not longer.
    for (KnowledgeEntryEntity existing : entries.findByAgentIdAndKindOrderByUpdatedAtDesc(agentId, LESSON)) {
      if (key.equals(existing.getAboutKey()) && text.equalsIgnoreCase(
          existing.getContent() == null ? "" : existing.getContent().strip())) {
        record(existing, "updated", actor);
        existing.setOccurrences(existing.getOccurrences() + occurrences);
        existing.setUpdatedAt(Instant.now());
        KnowledgeEntryEntity saved = entries.save(existing);
        events.changed(saved.getUserId(), MemoryEvents.UPDATED, MemoryNode.NOTE, saved.getId());
        return toDto(saved);
      }
    }

    KnowledgeEntryEntity entity = new KnowledgeEntryEntity();
    entity.setAgentId(agentId);
    entity.setUserId(agent.getUserId());
    entity.setKind(LESSON);
    entity.setAboutKey(key);
    entity.setAboutLabel(label);
    entity.setOccurrences(occurrences);
    entity.setTitle(label == null ? "Lesson" : "Lesson: " + label);
    entity.setContent(text);
    entity.setClassification(DataClassification.NONE);
    entity.setUpdatedAt(Instant.now());
    try {
      entity.setEmbedding(Vectors.fit(embeddings.embed(entity.getTitle() + "\n" + text)));
    } catch (RuntimeException e) {
      // A lesson is matched by its subject and words too; a provider being down must not lose it.
      log.warn("Embedding a lesson failed; keeping it without a vector: {}", e.getMessage());
    }
    KnowledgeEntryEntity saved = entries.save(entity);
    recordCreated(saved, actor);
    audit.record(saved.getUserId(), actor.type(), actor.id(), "knowledge.lesson.learned",
        saved.getId().toString(), Json.obj().put("agentId", agentId.toString()).put("about", key));
    events.changed(saved.getUserId(), MemoryEvents.CREATED, MemoryNode.NOTE, saved.getId());
    return toDto(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<KnowledgeEntry> lessons(UUID agentId) {
    return entries.findByAgentIdAndKindOrderByUpdatedAtDesc(agentId, LESSON).stream()
        .filter(e -> !e.isRestricted()).map(this::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<KnowledgeEntry> noteIndex(UUID agentId, int limit) {
    return entries.findByAgentIdAndKindAndRestrictedFalseOrderByUpdatedAtDesc(agentId, NOTE,
            PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
        .stream().map(this::toDto).toList();
  }

  private static String clip(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }

  private KnowledgeEntryEntity ownEntry(UUID agentId, UUID entryId) {
    KnowledgeEntryEntity entity = entries.findById(entryId)
        .orElseThrow(() -> new NotFoundException("Knowledge entry", entryId));
    requireOwnNamespace(agentId, entity.getAgentId());
    return entity;
  }

  private void record(KnowledgeEntryEntity entity, String change, Actor actor) {
    KnowledgeHistoryEntity h = new KnowledgeHistoryEntity();
    h.setEntryId(entity.getId());
    h.setUserId(entity.getUserId());
    h.setChange(change);
    h.setPreviousTitle(entity.getTitle());
    h.setPreviousContent(entity.getContent());
    h.setPreviousAgentId(entity.getAgentId());
    h.setActorType(actor.type());
    h.setActorId(actor.id());
    h.setContextRef(actor.contextRef());
    history.save(h);
  }

  /** A new entry's first history line: who made it, and in which run. */
  private void recordCreated(KnowledgeEntryEntity entity, Actor actor) {
    KnowledgeHistoryEntity h = new KnowledgeHistoryEntity();
    h.setEntryId(entity.getId());
    h.setUserId(entity.getUserId());
    h.setChange("created");
    h.setActorType(actor.type());
    h.setActorId(actor.id());
    h.setContextRef(actor.contextRef());
    history.save(h);
  }

  private static com.fasterxml.jackson.databind.node.ObjectNode classificationOf(KnowledgeEntryEntity e) {
    return Json.obj().put("classification", String.valueOf(e.getClassification()));
  }

  /** A stamp in each entry's passport; a failure here never fails the agent's read. */
  private void stampReads(UUID agentId, List<KnowledgeEntry> read) {
    if (read.isEmpty()) return;
    try {
      recorder.ifAvailable(r -> r.notes(read.get(0).userId(), agentId,
          read.stream().map(KnowledgeEntry::id).toList()));
    } catch (RuntimeException e) {
      log.debug("Could not record knowledge access", e);
    }
  }

  private void requireOwnNamespace(UUID callingAgent, UUID targetNamespace) {
    if (!callingAgent.equals(targetNamespace)) {
      // Worth an audit entry in its own right: this should never happen in normal operation.
      agents.findById(callingAgent).ifPresent(agent -> audit.record(agent.getUserId(),
          ActorType.AGENT, callingAgent, "knowledge.isolation_violation",
          String.valueOf(targetNamespace), null));
      throw new IsolationViolationException(callingAgent, targetNamespace);
    }
  }

  private List<KnowledgeVectorSearch.ScoredId> semanticIds(UUID agentId, String query, int limit) {
    if (query == null || query.isBlank()) return List.of();
    try {
      return vectorSearch.nearest(agentId, embeddings.embed(query), limit);
    } catch (RuntimeException e) {
      // pgvector may be unavailable (for instance a database without the extension); the lexical
      // path still answers, so degrade rather than fail the agent's turn.
      log.debug("Vector search unavailable, falling back to lexical search", e);
      return List.of();
    }
  }

  private String embeddingText(KnowledgeEntry entry) {
    StringBuilder sb = new StringBuilder();
    if (entry.title() != null) sb.append(entry.title()).append('\n');
    if (entry.content() != null) sb.append(entry.content());
    if (entry.data() != null && !entry.data().isNull()) sb.append('\n').append(Json.write(entry.data()));
    return sb.toString();
  }

  private KnowledgeEntry toDto(KnowledgeEntryEntity e) {
    KnowledgeEntry.Lesson lesson = e.isLesson()
        ? new KnowledgeEntry.Lesson(e.getAboutKey(), e.getAboutLabel(), e.getOccurrences()) : null;
    return new KnowledgeEntry(e.getId(), e.getUserId(), e.getAgentId(), e.getTitle(), e.getContent(),
        e.getData(), e.getClassification(), e.getCreatedAt(), e.getUpdatedAt(), null, e.isRestricted(),
        lesson);
  }
}
