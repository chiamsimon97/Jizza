package com.jizza.knowledge.store;

import com.jizza.common.audit.AuditLog;
import com.jizza.common.enums.ActorType;
import com.jizza.common.enums.DataClassification;
import com.jizza.common.error.IsolationViolationException;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.knowledge.KnowledgeEntry;
import com.jizza.common.knowledge.KnowledgeStore;
import com.jizza.common.util.Json;
import com.jizza.knowledge.embedding.EmbeddingProvider;
import com.jizza.persistence.entity.AgentEntity;
import com.jizza.persistence.entity.KnowledgeEntryEntity;
import com.jizza.persistence.repository.AgentRepository;
import com.jizza.persistence.repository.KnowledgeEntryRepository;
import com.jizza.persistence.support.KnowledgeVectorSearch;
import com.jizza.persistence.support.Vectors;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-agent knowledge with isolation enforced here, in code, rather than left to prompting (R22).
 *
 * <p>The rule is structural: the calling agent id is the namespace, every query is scoped by it in
 * the {@code where} clause, and any entry that arrives claiming a different {@code agentId} is
 * rejected outright. There is no code path through this class that reads or writes another agent's
 * namespace.
 */
@Service
public class NamespacedKnowledgeStore implements KnowledgeStore {

  private static final Logger log = LoggerFactory.getLogger(NamespacedKnowledgeStore.class);

  private final KnowledgeEntryRepository entries;
  private final AgentRepository agents;
  private final EmbeddingProvider embeddings;
  private final KnowledgeVectorSearch vectorSearch;
  private final AuditLog audit;

  public NamespacedKnowledgeStore(KnowledgeEntryRepository entries, AgentRepository agents,
                                  EmbeddingProvider embeddings, KnowledgeVectorSearch vectorSearch,
                                  AuditLog audit) {
    this.entries = entries;
    this.agents = agents;
    this.embeddings = embeddings;
    this.vectorSearch = vectorSearch;
    this.audit = audit;
  }

  @Override
  @Transactional(readOnly = true)
  public List<KnowledgeEntry> search(UUID agentId, String query, int k) {
    int limit = Math.max(1, Math.min(k, 50));
    Map<UUID, KnowledgeEntry> found = new LinkedHashMap<>();

    for (var scored : semanticIds(agentId, query, limit)) {
      entries.findById(scored.id())
          .filter(e -> e.getAgentId().equals(agentId))
          .ifPresent(e -> found.put(e.getId(), toDto(e).withScore(1.0 - scored.distance())));
    }
    if (found.size() < limit) {
      for (var entity : entries.searchLexical(agentId, query == null ? "" : query,
          PageRequest.of(0, limit))) {
        found.putIfAbsent(entity.getId(), toDto(entity));
      }
    }
    return new ArrayList<>(found.values()).subList(0, Math.min(found.size(), limit));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<KnowledgeEntry> get(UUID agentId, UUID entryId) {
    return entries.findById(entryId)
        .map(entity -> {
          requireOwnNamespace(agentId, entity.getAgentId());
          return toDto(entity);
        });
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

    // The audit trail records that knowledge changed and how it is classified, never its content:
    // health and financial detail does not belong in a log (R13).
    audit.record(agent.getUserId(), ActorType.AGENT, agentId, "knowledge.upsert", saved.getTitle(),
        Json.obj().put("entryId", saved.getId().toString())
            .put("classification", String.valueOf(saved.getClassification())));
    return toDto(saved);
  }

  @Override
  @Transactional
  public void delete(UUID agentId, UUID entryId) {
    entries.findById(entryId).ifPresent(entity -> {
      requireOwnNamespace(agentId, entity.getAgentId());
      entries.delete(entity);
      audit.record(entity.getUserId(), ActorType.AGENT, agentId, "knowledge.delete",
          entity.getTitle(), null);
    });
  }

  @Override
  @Transactional(readOnly = true)
  public List<KnowledgeEntry> list(UUID agentId, int limit) {
    return entries.findByAgentIdOrderByUpdatedAtDesc(agentId,
            PageRequest.of(0, Math.max(1, Math.min(limit, 200))))
        .stream().map(this::toDto).toList();
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
    return new KnowledgeEntry(e.getId(), e.getUserId(), e.getAgentId(), e.getTitle(), e.getContent(),
        e.getData(), e.getClassification(), e.getCreatedAt(), e.getUpdatedAt(), null);
  }
}
