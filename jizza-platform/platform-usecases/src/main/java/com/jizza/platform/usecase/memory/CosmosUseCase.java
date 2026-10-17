package com.jizza.platform.usecase.memory;

import com.jizza.engine.api.cosmos.BodyContents;
import com.jizza.engine.api.cosmos.CosmosQueryService;
import com.jizza.engine.api.cosmos.CosmosSnapshot;
import com.jizza.engine.api.cosmos.ItemPage;
import com.jizza.engine.api.cosmos.MemoryNode;
import com.jizza.engine.api.cosmos.MemorySearchHit;
import com.jizza.engine.api.cosmos.MemorySearchQuery;
import com.jizza.engine.api.cosmos.Passport;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.knowledge.KnowledgeStore;
import com.jizza.engine.api.memory.Actor;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryDraft;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.api.memory.MemoryWrite;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The Memory Cosmos for the user: everything Jizza remembers, who can use it, where it came from
 * and who has, and the actions that change who can use it.
 *
 * <p>The rules that keep agents apart stay where they are enforced. A fact is shared by adding an
 * agent's domain to it, which every memory read already honours. A note is never shared, because
 * an agent's own notes are its namespace (R22); the user can move it to another agent, or promote
 * it into their shared memory, which is a new fact the user chose to make.
 *
 * <p>Every change is recorded in the item's history and in the audit log by the store that makes
 * it, without content (R13). Step-up for the consequential ones is on the routes.
 */
@Service
public class CosmosUseCase {

  private static final int MAX_PROMOTED_CHARS = 2000;

  private final CosmosQueryService cosmos;
  private final MemoryStore memory;
  private final KnowledgeStore knowledge;
  private final com.jizza.engine.api.orchestrator.AgentDirectory agents;

  public CosmosUseCase(CosmosQueryService cosmos, MemoryStore memory, KnowledgeStore knowledge,
                       com.jizza.engine.api.orchestrator.AgentDirectory agents) {
    this.cosmos = cosmos;
    this.memory = memory;
    this.knowledge = knowledge;
    this.agents = agents;
  }

  // ── reading ─────────────────────────────────────────────────────────────────────────────────

  public CosmosSnapshot snapshot(UUID userId) {
    return cosmos.snapshot(userId);
  }

  public ItemPage items(UUID userId, String bodyId, String cursor, Integer limit,
                        boolean includeInactive) {
    return cosmos.itemsOf(userId, bodyId, cursor, limit == null ? 0 : limit, includeInactive);
  }

  public List<MemorySearchHit> search(UUID userId, MemorySearchQuery query) {
    return cosmos.search(userId, query);
  }

  public Passport passport(UUID userId, String kind, UUID id, String cursor) {
    return cosmos.passport(userId, itemKind(kind), id, cursor);
  }

  public Passport reveal(UUID userId, String kind, UUID id) {
    return cosmos.reveal(userId, itemKind(kind), id);
  }

  // ── facts ───────────────────────────────────────────────────────────────────────────────────

  /** Lets one more agent see a fact: its domain is added to the fact's. */
  public Memory share(UUID userId, UUID memoryId, UUID agentId) {
    return memory.share(userId, memoryId, domainOf(owned(userId, agentId)), Actor.user(userId));
  }

  public Memory unshare(UUID userId, UUID memoryId, UUID agentId) {
    return memory.unshare(userId, memoryId, domainOf(owned(userId, agentId)), Actor.user(userId));
  }

  /**
   * Replaces who can see a fact. Every domain must belong to one of the user's agents, so a fact
   * cannot be parked where nobody can see it; an empty list means every agent.
   */
  public Memory move(UUID userId, UUID memoryId, List<String> domains) {
    Set<String> held = new LinkedHashSet<>();
    for (AgentDto a : agents.list(userId)) {
      if (a.domain() != null && !a.domain().isBlank()) held.add(a.domain().strip().toLowerCase(Locale.ROOT));
    }
    List<String> target = new ArrayList<>();
    for (String d : domains == null ? List.<String>of() : domains) {
      String domain = d == null ? "" : d.strip().toLowerCase(Locale.ROOT);
      if (!held.contains(domain)) throw new ValidationException("None of your agents works in '" + d + "'");
      target.add(domain);
    }
    return memory.setDomains(userId, memoryId, target, Actor.user(userId));
  }

  public Memory restrictFact(UUID userId, UUID memoryId, boolean restricted) {
    return memory.restrict(userId, memoryId, restricted, Actor.user(userId));
  }

  // ── notes ───────────────────────────────────────────────────────────────────────────────────

  public KnowledgeEntry editNote(UUID userId, UUID agentId, UUID noteId, String title, String content) {
    owned(userId, agentId);
    return knowledge.edit(agentId, noteId, title, content, Actor.user(userId));
  }

  public KnowledgeEntry moveNote(UUID userId, UUID agentId, UUID noteId, UUID toAgentId) {
    owned(userId, agentId);
    owned(userId, toAgentId);
    return knowledge.move(agentId, noteId, toAgentId, Actor.user(userId));
  }

  public KnowledgeEntry restrictNote(UUID userId, UUID agentId, UUID noteId, boolean restricted) {
    owned(userId, agentId);
    return knowledge.restrict(agentId, noteId, restricted, Actor.user(userId));
  }

  /**
   * Copies a note into the user's shared memory as a fact the user stands behind, visible by the
   * default rules for its category (and restricted if the note was, in the same transaction, so no
   * agent can read it in between). Optionally removes the note.
   */
  @org.springframework.transaction.annotation.Transactional
  public MemoryWrite promoteNote(UUID userId, UUID agentId, UUID noteId, MemoryCategory category,
                                 boolean removeNote) {
    owned(userId, agentId);
    Passport note = cosmos.reveal(userId, MemoryNode.NOTE, noteId);
    if (note.origin().agentId() == null || !note.origin().agentId().equals(agentId)) {
      throw new NotFoundException("Note", noteId);
    }
    String text = note.content() == null || note.content().isBlank()
        ? note.title() : note.title() + ": " + note.content();
    if (text == null || text.isBlank()) throw new ValidationException("That note is empty");
    if (text.length() > MAX_PROMOTED_CHARS) text = text.substring(0, MAX_PROMOTED_CHARS);

    MemoryDraft draft = new MemoryDraft(category == null ? MemoryCategory.GENERAL : category, text,
        null, null, null, 1.0, null, MemorySource.IMPORT, "note:" + noteId, true, List.of(), null, null);
    MemoryWrite written = memory.remember(userId, draft, Actor.user(userId));
    if (note.item().restricted()) {
      memory.restrict(userId, written.memory().id(), true, Actor.user(userId));
    }
    if (removeNote) knowledge.delete(agentId, noteId);
    return written;
  }

  // ── reset ───────────────────────────────────────────────────────────────────────────────────

  /** What a reset forgot. */
  public record ResetResult(int facts, int notes, int subjects) {}

  /**
   * Forgets one part of the sky: every fact and note placed in {@code bodyId} or any moon under
   * it, and the subjects that are moons there. For the sun, that is all of the user's memory.
   * Files stay; the notes read from them go. Each deletion goes through its store, so history,
   * read stamps, audit and live updates stay right. Permanent; the route asks for step-up.
   */
  @org.springframework.transaction.annotation.Transactional
  public ResetResult reset(UUID userId, String bodyId) {
    if (bodyId == null || bodyId.isBlank()) throw new ValidationException("Say which part of your memory to reset");
    BodyContents contents = cosmos.contentsOf(userId, bodyId);
    Actor actor = Actor.user(userId);
    for (UUID id : contents.factIds()) memory.forget(userId, id, actor);
    for (BodyContents.Note n : contents.notes()) knowledge.delete(n.agentId(), n.id());
    for (UUID id : contents.subjectIds()) memory.deleteSubject(userId, id, actor);
    return new ResetResult(contents.factIds().size(), contents.notes().size(), contents.subjectIds().size());
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────

  private AgentDto owned(UUID userId, UUID agentId) {
    if (agentId == null) throw new ValidationException("Say which agent");
    return agents.byId(agentId)
        .filter(a -> a.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Agent", agentId));
  }

  private static String domainOf(AgentDto agent) {
    if (agent.domain() == null || agent.domain().isBlank()) {
      throw new ValidationException(agent.name() + " has no domain of its own; share with every "
          + "agent instead");
    }
    return agent.domain().strip().toLowerCase(Locale.ROOT);
  }

  private static String itemKind(String kind) {
    String k = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT);
    if (!k.equals(MemoryNode.FACT) && !k.equals(MemoryNode.NOTE)) {
      throw new ValidationException("A memory item is a fact or a note");
    }
    return k;
  }
}
