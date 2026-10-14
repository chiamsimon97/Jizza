package com.jizza.engine.memory.cosmos;

import com.jizza.engine.api.cosmos.CelestialBody;
import com.jizza.engine.api.cosmos.CosmosBodyProvider;
import com.jizza.engine.api.cosmos.CosmosQueryService;
import com.jizza.engine.api.cosmos.CosmosSnapshot;
import com.jizza.engine.api.cosmos.ItemPage;
import com.jizza.engine.api.cosmos.MemoryNode;
import com.jizza.engine.api.cosmos.MemorySearchHit;
import com.jizza.engine.api.cosmos.MemorySearchQuery;
import com.jizza.engine.api.cosmos.Passport;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryScope;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.knowledge.embedding.EmbeddingProvider;
import com.jizza.engine.memory.config.MemoryScopes;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.FileEntity;
import com.jizza.engine.persistence.entity.KnowledgeEntryEntity;
import com.jizza.engine.persistence.entity.MemoryAccessEntity;
import com.jizza.engine.persistence.entity.MemoryEntryEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.FileRepository;
import com.jizza.engine.persistence.repository.KnowledgeEntryRepository;
import com.jizza.engine.persistence.repository.KnowledgeHistoryRepository;
import com.jizza.engine.persistence.repository.MemoryAccessRepository;
import com.jizza.engine.persistence.repository.MemoryEntryRepository;
import com.jizza.engine.persistence.repository.MemoryHistoryRepository;
import com.jizza.engine.persistence.repository.MemorySubjectRelationRepository;
import com.jizza.engine.persistence.repository.MemorySubjectRepository;
import com.jizza.engine.persistence.repository.MessageRepository;
import com.jizza.engine.persistence.support.CosmosQueries;
import com.jizza.engine.persistence.support.CosmosQueries.FactRef;
import com.jizza.engine.persistence.support.CosmosQueries.NoteRef;
import com.jizza.engine.persistence.support.KnowledgeVectorSearch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the Memory Cosmos from both stores: the user's memory (facts and the subjects they are
 * about) and each agent's own notes and files, plus the moons other modules contribute.
 *
 * <p>Runs as the user: it sees every domain and restricted items exist in it, but a restricted
 * item's words are withheld everywhere except {@link #reveal}, which the API puts behind a fresh
 * step-up.
 */
@Service
public class DefaultCosmosQueryService implements CosmosQueryService {

  private static final Logger log = LoggerFactory.getLogger(DefaultCosmosQueryService.class);

  static final int MAX_PAGE = 200;
  static final int STAMP_PAGE = 50;
  static final int MAX_HITS = 50;
  private static final int RRF_K = 60;
  private static final int LABEL_CHARS = 120;

  private final AgentRepository agents;
  private final MemorySubjectRepository subjects;
  private final MemorySubjectRelationRepository relations;
  private final MemoryEntryRepository memories;
  private final MemoryHistoryRepository history;
  private final KnowledgeEntryRepository notes;
  private final KnowledgeHistoryRepository noteHistory;
  private final FileRepository files;
  private final MemoryAccessRepository access;
  private final MessageRepository messages;
  private final CosmosQueries queries;
  private final MemoryStore store;
  private final KnowledgeVectorSearch noteVectors;
  private final EmbeddingProvider embeddings;
  private final MemoryScopes scopes;
  private final ObjectProvider<CosmosBodyProvider> providers;

  public DefaultCosmosQueryService(AgentRepository agents, MemorySubjectRepository subjects,
                                   MemorySubjectRelationRepository relations,
                                   MemoryEntryRepository memories, MemoryHistoryRepository history,
                                   KnowledgeEntryRepository notes,
                                   KnowledgeHistoryRepository noteHistory, FileRepository files,
                                   MemoryAccessRepository access, MessageRepository messages,
                                   CosmosQueries queries, MemoryStore store,
                                   KnowledgeVectorSearch noteVectors, EmbeddingProvider embeddings,
                                   MemoryScopes scopes, ObjectProvider<CosmosBodyProvider> providers) {
    this.agents = agents;
    this.subjects = subjects;
    this.relations = relations;
    this.memories = memories;
    this.history = history;
    this.notes = notes;
    this.noteHistory = noteHistory;
    this.files = files;
    this.access = access;
    this.messages = messages;
    this.queries = queries;
    this.store = store;
    this.noteVectors = noteVectors;
    this.embeddings = embeddings;
    this.scopes = scopes;
    this.providers = providers;
  }

  // ── snapshot ────────────────────────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public CosmosSnapshot snapshot(UUID userId) {
    Placed placed = place(userId, false);
    CosmosLayout layout = placed.layout;

    Map<String, long[]> counts = new HashMap<>();
    Map<String, Instant> activity = new HashMap<>();
    for (FactRef f : placed.facts) {
      String home = layout.homeOf(f);
      long[] c = counts.computeIfAbsent(home, k -> new long[2]);
      c[0]++;
      if (f.domains().isEmpty() || f.domains().size() > 1) c[1]++;
      activity.merge(home, f.updatedAt(), CosmosLayout::later);
    }
    for (NoteRef n : placed.notes) {
      String home = layout.homeOf(n);
      counts.computeIfAbsent(home, k -> new long[2])[0]++;
      activity.merge(home, n.updatedAt(), CosmosLayout::later);
    }
    List<CelestialBody> bodies = new ArrayList<>();
    for (CelestialBody b : layout.bodies.values()) {
      long[] c = counts.getOrDefault(b.bodyId(), new long[2]);
      bodies.add(b.withCounts(c[0], c[1], CosmosLayout.later(b.lastActivityAt(), activity.get(b.bodyId()))));
    }
    return new CosmosSnapshot(bodies, List.copyOf(layout.links), Instant.now());
  }

  // ── items around a body ─────────────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public ItemPage itemsOf(UUID userId, String bodyId, String cursor, int limit,
                          boolean includeInactive) {
    Placed placed = place(userId, includeInactive);
    CosmosLayout layout = placed.layout;
    if (!layout.bodies.containsKey(bodyId)) throw new NotFoundException("Body", bodyId);

    record Slot(String kind, UUID id, Instant createdAt) {}
    List<Slot> here = new ArrayList<>();
    Map<UUID, FactRef> factRefs = new HashMap<>();
    for (FactRef f : placed.facts) {
      if (layout.homeOf(f).equals(bodyId)) {
        here.add(new Slot(MemoryNode.FACT, f.id(), f.createdAt()));
        factRefs.put(f.id(), f);
      }
    }
    for (NoteRef n : placed.notes) {
      if (layout.homeOf(n).equals(bodyId)) here.add(new Slot(MemoryNode.NOTE, n.id(), n.createdAt()));
    }
    here.sort(Comparator.comparing(Slot::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(s -> s.id().toString()));

    int offset = offset(cursor);
    int size = Math.max(1, Math.min(limit <= 0 ? MAX_PAGE : limit, MAX_PAGE));
    List<Slot> page = here.subList(Math.min(offset, here.size()), Math.min(offset + size, here.size()));

    Map<UUID, MemoryEntryEntity> factRows = memories.findAllById(
            page.stream().filter(s -> s.kind().equals(MemoryNode.FACT)).map(Slot::id).toList())
        .stream().collect(Collectors.toMap(MemoryEntryEntity::getId, Function.identity()));
    Map<UUID, KnowledgeEntryEntity> noteRows = notes.findAllById(
            page.stream().filter(s -> s.kind().equals(MemoryNode.NOTE)).map(Slot::id).toList())
        .stream().collect(Collectors.toMap(KnowledgeEntryEntity::getId, Function.identity()));

    List<MemoryNode> items = new ArrayList<>();
    for (Slot s : page) {
      if (s.kind().equals(MemoryNode.FACT)) {
        MemoryEntryEntity row = factRows.get(s.id());
        if (row != null) items.add(factNode(layout, row, factRefs.get(s.id()), false));
      } else {
        KnowledgeEntryEntity row = noteRows.get(s.id());
        if (row != null) items.add(noteNode(layout, row, placed.noteRef(s.id()), false));
      }
    }
    String next = offset + size < here.size() ? String.valueOf(offset + size) : null;
    return new ItemPage(items, next);
  }

  // ── search ──────────────────────────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<MemorySearchHit> search(UUID userId, MemorySearchQuery query) {
    String q = query.q() == null ? "" : query.q().strip();
    if (q.isEmpty() || q.length() > 500) throw new ValidationException("Search needs 1 to 500 characters");
    Placed placed = place(userId, false);
    CosmosLayout layout = placed.layout;

    // Facts: the memory's own hybrid search, over every domain. Notes: meaning and keywords across
    // every agent's namespace. Merged by rank (reciprocal-rank fusion), which needs no common scale.
    Map<String, Double> fused = new LinkedHashMap<>();
    List<Memory> facts = store.search(userId, q, MemoryScope.everything(), MAX_HITS);
    for (int i = 0; i < facts.size(); i++) {
      fused.merge(MemoryNode.FACT + ":" + facts.get(i).id(), 1.0 / (RRF_K + i + 1), Double::sum);
    }
    List<UUID> noteHits = new ArrayList<>();
    try {
      noteVectors.nearestForUser(userId, embeddings.embed(q), MAX_HITS).stream()
          .filter(s -> s.distance() <= 0.8)
          .forEach(s -> noteHits.add(s.id()));
    } catch (RuntimeException e) {
      log.debug("Vector search over notes unavailable; using keywords only", e);
    }
    for (int i = 0; i < noteHits.size(); i++) {
      fused.merge(MemoryNode.NOTE + ":" + noteHits.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
    }
    List<KnowledgeEntryEntity> lexical = notes.searchLexicalForUser(userId, q, PageRequest.of(0, MAX_HITS));
    for (int i = 0; i < lexical.size(); i++) {
      fused.merge(MemoryNode.NOTE + ":" + lexical.get(i).getId(), 1.0 / (RRF_K + i + 1), Double::sum);
    }

    Map<UUID, FactRef> factRefs = placed.facts.stream().collect(Collectors.toMap(FactRef::id, f -> f));
    Map<UUID, MemoryEntryEntity> factRows = memories.findAllById(
            facts.stream().map(Memory::id).toList()).stream()
        .collect(Collectors.toMap(MemoryEntryEntity::getId, Function.identity()));
    List<UUID> noteIds = fused.keySet().stream().filter(k -> k.startsWith(MemoryNode.NOTE + ":"))
        .map(k -> UUID.fromString(k.substring(MemoryNode.NOTE.length() + 1))).toList();
    Map<UUID, KnowledgeEntryEntity> noteRows = notes.findAllById(noteIds).stream()
        .filter(n -> n.getUserId().equals(userId))
        .collect(Collectors.toMap(KnowledgeEntryEntity::getId, Function.identity()));

    List<MemorySearchHit> hits = new ArrayList<>();
    for (var e : fused.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).toList()) {
      String[] key = e.getKey().split(":", 2);
      UUID id = UUID.fromString(key[1]);
      MemoryNode node;
      if (key[0].equals(MemoryNode.FACT)) {
        MemoryEntryEntity row = factRows.get(id);
        FactRef ref = factRefs.get(id);
        if (row == null || ref == null || !matches(query, layout, row)) continue;
        node = factNode(layout, row, ref, false);
      } else {
        KnowledgeEntryEntity row = noteRows.get(id);
        NoteRef ref = placed.noteRef(id);
        if (row == null || ref == null || !matches(query, row)) continue;
        node = noteNode(layout, row, ref, false);
      }
      hits.add(new MemorySearchHit(node, layout.pathTo(node.parentBodyId()),
          node.redacted() ? null : node.label(), Math.round(e.getValue() * 100000) / 100000.0));
      if (hits.size() == MAX_HITS) break;
    }
    return hits;
  }

  private boolean matches(MemorySearchQuery query, CosmosLayout layout, MemoryEntryEntity row) {
    if (!query.categories().isEmpty()
        && !query.categories().contains(row.getCategory().name().toLowerCase(Locale.ROOT))) return false;
    if (query.classification() != null
        && !query.classification().equalsIgnoreCase(String.valueOf(row.getClassification()))) return false;
    if (query.status() != null && !query.status().equalsIgnoreCase(row.getStatus().name())) return false;
    if (!inWindow(query, row.getCreatedAt())) return false;
    if (!query.agentIds().isEmpty()) {
      List<String> domains = List.of(row.getDomains());
      return layout.allAgents().stream()
          .anyMatch(a -> query.agentIds().contains(a.getId())
              && scopes.forAgent(a).sees(domains, row.isRestricted()));
    }
    return true;
  }

  private static boolean matches(MemorySearchQuery query, KnowledgeEntryEntity row) {
    if (!query.categories().isEmpty() && !query.categories().contains(MemoryNode.NOTE)) return false;
    if (query.classification() != null
        && !query.classification().equalsIgnoreCase(String.valueOf(row.getClassification()))) return false;
    if (query.status() != null && !query.status().equalsIgnoreCase("active")) return false;
    if (!inWindow(query, row.getCreatedAt())) return false;
    return query.agentIds().isEmpty() || query.agentIds().contains(row.getAgentId());
  }

  private static boolean inWindow(MemorySearchQuery query, Instant at) {
    if (at == null) return query.from() == null && query.to() == null;
    return (query.from() == null || !at.isBefore(query.from()))
        && (query.to() == null || !at.isAfter(query.to()));
  }

  // ── passport ────────────────────────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public Passport passport(UUID userId, String itemKind, UUID id, String cursor) {
    return passport(userId, itemKind, id, cursor, false);
  }

  @Override
  @Transactional(readOnly = true)
  public Passport reveal(UUID userId, String itemKind, UUID id) {
    return passport(userId, itemKind, id, null, true);
  }

  private Passport passport(UUID userId, String itemKind, UUID id, String cursor, boolean reveal) {
    Placed placed = place(userId, true);
    CosmosLayout layout = placed.layout;
    if (MemoryNode.FACT.equals(itemKind)) return factPassport(userId, layout, placed, id, cursor, reveal);
    if (MemoryNode.NOTE.equals(itemKind)) return notePassport(userId, layout, placed, id, cursor, reveal);
    throw new ValidationException("A memory item is a fact or a note");
  }

  private Passport factPassport(UUID userId, CosmosLayout layout, Placed placed, UUID id,
                                String cursor, boolean reveal) {
    MemoryEntryEntity row = memories.findByIdAndUserId(id, userId)
        .orElseThrow(() -> new NotFoundException("Memory", id));
    FactRef ref = placed.facts.stream().filter(f -> f.id().equals(id)).findFirst()
        .orElseGet(() -> new FactRef(id, List.of(row.getDomains()), List.copyOf(row.getSubjectIds()),
            row.getCreatedAt(), row.getUpdatedAt(), row.isRestricted()));
    MemoryNode node = factNode(layout, row, ref, reveal);
    List<String> domains = List.of(row.getDomains());
    List<Passport.AgentRef> seeing = layout.allAgents().stream()
        .filter(a -> scopes.forAgent(a).sees(domains, row.isRestricted()))
        .map(DefaultCosmosQueryService::ref).toList();

    List<Passport.Stamp> stamps = new ArrayList<>();
    UUID createdBy = null;
    for (var h : history.findByMemoryIdOrderByCreatedAtAsc(id)) {
      if (h.getChange().equals("created")) createdBy = h.getActorId();
      stamps.add(new Passport.Stamp(h.getChange(), lower(h.getActorType()), h.getActorId(),
          actorLabel(layout, h.getActorType(), h.getActorId()), null, null, h.getCreatedAt(),
          h.getNote()));
    }
    stamps.addAll(reads(layout, MemoryNode.FACT, id));
    stamps.sort(Comparator.comparing(Passport.Stamp::at, Comparator.nullsLast(Comparator.reverseOrder())));

    Passport.Origin origin = factOrigin(layout, row, createdBy);
    int offset = offset(cursor);
    return new Passport(node, null, node.redacted() ? null : row.getContent(),
        new Passport.Visibility(domains.isEmpty() && !row.isRestricted(), domains, seeing), origin,
        page(stamps, offset), nextCursor(stamps, offset));
  }

  private Passport notePassport(UUID userId, CosmosLayout layout, Placed placed, UUID id,
                                String cursor, boolean reveal) {
    KnowledgeEntryEntity row = notes.findById(id).filter(n -> n.getUserId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Note", id));
    NoteRef ref = placed.noteRef(id);
    MemoryNode node = noteNode(layout, row, ref, reveal);
    AgentEntity owner = layout.agentsById.get(row.getAgentId());

    List<Passport.Stamp> stamps = new ArrayList<>();
    stamps.add(new Passport.Stamp("created", "agent", row.getAgentId(),
        owner == null ? "An agent" : owner.getName(), null, null, row.getCreatedAt(), null));
    for (var h : noteHistory.findByEntryIdOrderByCreatedAtAsc(id)) {
      String note = h.getChange().equals("moved") && h.getPreviousAgentId() != null
          ? "from " + agentName(layout, h.getPreviousAgentId()) : null;
      stamps.add(new Passport.Stamp(h.getChange(), lower(h.getActorType()), h.getActorId(),
          actorLabel(layout, h.getActorType(), h.getActorId()), null, null, h.getCreatedAt(), note));
    }
    stamps.addAll(reads(layout, MemoryNode.NOTE, id));
    stamps.sort(Comparator.comparing(Passport.Stamp::at, Comparator.nullsLast(Comparator.reverseOrder())));

    Passport.Origin origin;
    FileEntity file = ref == null || ref.fileId() == null ? null : files.findById(ref.fileId()).orElse(null);
    if (file != null) {
      origin = new Passport.Origin("file", file.getFilename(), "file:" + file.getId(), null,
          file.getId(), row.getAgentId());
    } else {
      origin = new Passport.Origin("note", "Saved by " + (owner == null ? "an agent" : owner.getName()),
          null, null, null, row.getAgentId());
    }
    List<Passport.AgentRef> seeing = owner == null || row.isRestricted() ? List.of() : List.of(ref(owner));
    int offset = offset(cursor);
    return new Passport(node, node.redacted() ? null : row.getTitle(),
        node.redacted() ? null : row.getContent(), new Passport.Visibility(false, List.of(), seeing),
        origin, page(stamps, offset), nextCursor(stamps, offset));
  }

  private List<Passport.Stamp> reads(CosmosLayout layout, String kind, UUID id) {
    List<Passport.Stamp> out = new ArrayList<>();
    for (MemoryAccessEntity a : access.findByItemKindAndItemIdOrderByOccurredAtDesc(kind, id,
        PageRequest.of(0, 500))) {
      out.add(new Passport.Stamp("read", "agent", a.getAgentId(),
          a.getAgentId() == null ? "An agent" : agentName(layout, a.getAgentId()), a.getContextKind(),
          a.getContextRef(), a.getOccurredAt(), null));
    }
    return out;
  }

  private Passport.Origin factOrigin(CosmosLayout layout, MemoryEntryEntity row, UUID createdBy) {
    String ref = row.getSourceRef();
    return switch (row.getSource()) {
      case USER -> new Passport.Origin("user", "You told Jizza", ref, null, null, null);
      case AGENT -> new Passport.Origin("agent",
          "Saved by " + (createdBy == null ? "an agent" : agentName(layout, createdBy)), ref, null,
          null, createdBy);
      case CONVERSATION -> new Passport.Origin("conversation", "Learned from a conversation", ref,
          conversationOf(ref), null, null);
      case IMPORT -> new Passport.Origin("import",
          ref != null && ref.startsWith("note:") ? "Promoted from an agent's note" : "Imported", ref,
          null, null, null);
    };
  }

  private UUID conversationOf(String sourceRef) {
    if (sourceRef == null || !sourceRef.startsWith("message:")) return null;
    try {
      return messages.findById(UUID.fromString(sourceRef.substring("message:".length())))
          .map(m -> m.getConversationId()).orElse(null);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  // ── nodes ───────────────────────────────────────────────────────────────────────────────────

  private MemoryNode factNode(CosmosLayout layout, MemoryEntryEntity row, FactRef ref, boolean reveal) {
    FactRef placement = ref != null ? ref : new FactRef(row.getId(), List.of(row.getDomains()),
        row.getSubjectIds().stream().sorted(Comparator.comparing(UUID::toString)).toList(),
        row.getCreatedAt(), row.getUpdatedAt(), row.isRestricted());
    String home = layout.homeOf(placement);
    boolean redacted = row.isRestricted() && !reveal;
    return new MemoryNode(MemoryNode.FACT, row.getId(), home,
        redacted ? null : label(row.getContent()), layout.agentKeyOf(placement, home),
        lower(row.getCategory()), lower(row.getClassification()), row.isRestricted(), redacted,
        lower(row.getStatus()), row.isConfirmed(), row.getAccessCount(), row.getLastAccessedAt(),
        row.getCreatedAt(), layout.tethersOf(placement, home));
  }

  private MemoryNode noteNode(CosmosLayout layout, KnowledgeEntryEntity row, NoteRef ref, boolean reveal) {
    NoteRef placement = ref != null ? ref
        : new NoteRef(row.getId(), row.getAgentId(), null, row.getCreatedAt(), row.getUpdatedAt());
    String home = layout.homeOf(placement);
    AgentEntity owner = layout.agentsById.get(row.getAgentId());
    boolean redacted = row.isRestricted() && !reveal;
    return new MemoryNode(MemoryNode.NOTE, row.getId(), home, redacted ? null : label(row.getTitle()),
        owner == null ? null : owner.getKey(), MemoryNode.NOTE, lower(row.getClassification()),
        row.isRestricted(), redacted, "active", true, row.getAccessCount(), row.getLastAccessedAt(),
        row.getCreatedAt(), List.of());
  }

  // ── placement ───────────────────────────────────────────────────────────────────────────────

  private record Placed(CosmosLayout layout, List<FactRef> facts, List<NoteRef> notes,
                        Map<UUID, NoteRef> notesById) {
    NoteRef noteRef(UUID id) { return notesById.get(id); }
  }

  private Placed place(UUID userId, boolean includeInactive) {
    AgentEntity orchestrator = agents.findByUserIdAndIsOrchestratorTrue(userId)
        .orElseThrow(() -> new NotFoundException("Orchestrator agent for user", userId));
    CosmosLayout layout = new CosmosLayout(orchestrator,
        agents.findByUserIdAndIsOrchestratorFalseAndEnabledTrue(userId));
    layout.addSubjects(subjects.findByUserIdOrderByNameAsc(userId), relations.findByUserId(userId));

    List<NoteRef> noteRefs = queries.notes(userId).stream()
        .filter(n -> layout.agentsById.containsKey(n.agentId())).toList();
    List<FileEntity> agentFiles = files.findByUserId(userId).stream()
        .filter(f -> f.getAgentId() != null && layout.agentsById.containsKey(f.getAgentId()))
        .sorted(Comparator.comparing(FileEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
    layout.addNotesAndFiles(noteRefs, agentFiles);

    for (AgentEntity agent : layout.allAgents()) {
      for (CosmosBodyProvider provider : providers.orderedStream().toList()) {
        try {
          layout.addProvided(agent, provider.bodies(userId, layout.cosmosAgent(agent)));
        } catch (RuntimeException e) {
          // One module failing to describe its moons must not take the whole Cosmos down.
          log.warn("{} could not contribute bodies", provider.getClass().getSimpleName(), e);
        }
      }
    }
    layout.linkConnectors();

    Map<UUID, NoteRef> byId = new HashMap<>();
    noteRefs.forEach(n -> byId.put(n.id(), n));
    return new Placed(layout, queries.facts(userId, includeInactive), noteRefs, byId);
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────

  private static Passport.AgentRef ref(AgentEntity a) {
    return new Passport.AgentRef(a.getId(), a.getKey(), a.getName());
  }

  private static String agentName(CosmosLayout layout, UUID agentId) {
    AgentEntity a = layout.agentsById.get(agentId);
    return a == null ? "A former agent" : a.getName();
  }

  private static String actorLabel(CosmosLayout layout, ActorType type, UUID id) {
    if (type == null) return null;
    return switch (type) {
      case USER -> "You";
      case AGENT -> id == null ? "An agent" : agentName(layout, id);
      case SYSTEM -> layout.orchestrator.getName();
    };
  }

  private static String label(String text) {
    if (text == null) return null;
    String t = text.strip().replaceAll("\\s+", " ");
    return t.length() <= LABEL_CHARS ? t : t.substring(0, LABEL_CHARS - 1) + "…";
  }

  private static String lower(Object value) {
    return value == null ? null : value.toString().toLowerCase(Locale.ROOT);
  }

  private static int offset(String cursor) {
    if (cursor == null || cursor.isBlank()) return 0;
    try {
      return Math.max(0, Integer.parseInt(cursor));
    } catch (NumberFormatException e) {
      throw new ValidationException("Not a valid cursor");
    }
  }

  private static List<Passport.Stamp> page(List<Passport.Stamp> stamps, int offset) {
    return List.copyOf(stamps.subList(Math.min(offset, stamps.size()),
        Math.min(offset + STAMP_PAGE, stamps.size())));
  }

  private static String nextCursor(List<Passport.Stamp> stamps, int offset) {
    return offset + STAMP_PAGE < stamps.size() ? String.valueOf(offset + STAMP_PAGE) : null;
  }
}
