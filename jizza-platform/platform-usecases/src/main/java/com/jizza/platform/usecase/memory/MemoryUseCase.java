package com.jizza.platform.usecase.memory;

import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import com.jizza.engine.api.enums.MemoryStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.memory.Actor;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryChange;
import com.jizza.engine.api.memory.MemoryContextProvider;
import com.jizza.engine.api.memory.MemoryDraft;
import com.jizza.engine.api.memory.MemoryFilter;
import com.jizza.engine.api.memory.MemoryScope;
import com.jizza.engine.api.memory.MemorySettings;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.api.memory.MemorySubject;
import com.jizza.engine.api.memory.MemoryWrite;
import com.jizza.engine.api.memory.SubjectDraft;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The user's view of their own memory (R48): everything Jizza remembers about them and the people,
 * accounts and plans in their life, which they can add to, correct, confirm and delete.
 *
 * <p>The user sees every domain -- the domain scoping exists to keep specialists apart, not to hide
 * anything from the person it is about. What the user writes is confirmed by definition and
 * outranks anything an agent or a conversation inferred.
 */
@Service
public class MemoryUseCase {

  private static final int MAX_LIST = 500;
  private static final int MAX_REQUEST_CHARS = 2000;

  /** A memory with what it said before each change. */
  public record MemoryDetail(Memory memory, List<MemoryChange> history) {}

  /** What an agent would be handed for a request, so the user can see memory working. */
  public record ContextPreview(AgentDto agent, String request, String context, List<UUID> memoryIds,
                               List<UUID> subjectIds) {}

  private final MemoryStore store;
  private final MemoryContextProvider context;
  private final AgentDirectory agents;

  public MemoryUseCase(MemoryStore store, MemoryContextProvider context, AgentDirectory agents) {
    this.store = store;
    this.context = context;
    this.agents = agents;
  }

  public List<Memory> list(UUID userId, MemoryCategory category, MemoryStatus status, UUID subjectId,
                           String query, Integer limit) {
    int n = limit == null ? 200 : Math.max(1, Math.min(limit, MAX_LIST));
    List<Memory> found = query != null && !query.isBlank()
        ? store.search(userId, query, MemoryScope.everything(), Math.min(n, 50))
        : store.list(userId, new MemoryFilter(category, status, subjectId, n));
    return found.stream().map(MemoryUseCase::shown).toList();
  }

  /** A restricted fact's words are shown only through the step-up guarded reveal. */
  static Memory shown(Memory m) {
    return m.restricted() ? m.redacted() : m;
  }

  public MemoryDetail detail(UUID userId, UUID memoryId) {
    Memory memory = store.get(userId, memoryId)
        .orElseThrow(() -> new NotFoundException("Memory", memoryId));
    if (memory.restricted()) return new MemoryDetail(memory.redacted(), List.of());
    return new MemoryDetail(memory, store.history(userId, memoryId));
  }

  public MemoryWrite create(UUID userId, MemoryDraft draft) {
    if (draft.category() == null) throw new ValidationException("Pick a category for this memory");
    MemoryDraft fromUser = new MemoryDraft(draft.category(), draft.content(), draft.factKey(),
        draft.data(), draft.classification(), 1.0, draft.importance(), MemorySource.USER, "user",
        true, draft.subjectIds() == null ? List.of() : draft.subjectIds(), draft.domains(),
        draft.expiresAt());
    return store.remember(userId, fromUser, Actor.user(userId));
  }

  public Memory correct(UUID userId, UUID memoryId, MemoryDraft changes) {
    if (changes.domains() != null) {
      Memory current = store.get(userId, memoryId).orElseThrow(() -> new NotFoundException("Memory", memoryId));
      if (!java.util.Set.copyOf(current.domains()).equals(java.util.Set.copyOf(changes.domains()))) {
        // Who can see a fact changes through move or share, which have their own checks.
        throw new ValidationException("Use move or share to change which agents can see this memory");
      }
    }
    return store.correct(userId, memoryId, changes, Actor.user(userId));
  }

  public Memory confirm(UUID userId, UUID memoryId) {
    return store.confirm(userId, memoryId, Actor.user(userId));
  }

  /** The user setting a fact aside: no agent uses it until it is restored. */
  public Memory retire(UUID userId, UUID memoryId) {
    return store.retire(userId, memoryId, "retired by user", Actor.user(userId));
  }

  public Memory restore(UUID userId, UUID memoryId) {
    return store.restore(userId, memoryId, Actor.user(userId));
  }

  public void forget(UUID userId, UUID memoryId) {
    store.forget(userId, memoryId, Actor.user(userId));
  }

  public List<MemorySubject> subjects(UUID userId) {
    return store.subjects(userId, MemoryScope.everything());
  }

  public MemorySubject createSubject(UUID userId, SubjectDraft draft) {
    if (draft.kind() == null) throw new ValidationException("Say what kind of subject this is");
    return store.upsertSubject(userId, draft, Actor.user(userId));
  }

  public MemorySubject updateSubject(UUID userId, UUID subjectId, SubjectDraft draft) {
    return store.updateSubject(userId, subjectId, draft, Actor.user(userId));
  }

  public void deleteSubject(UUID userId, UUID subjectId) {
    store.deleteSubject(userId, subjectId, Actor.user(userId));
  }

  public MemorySubject.Relation relate(UUID userId, UUID fromSubjectId, UUID toSubjectId,
                                       String relation) {
    return store.relate(userId, fromSubjectId, toSubjectId, relation, Actor.user(userId));
  }

  public void unrelate(UUID userId, UUID relationId) {
    store.unrelate(userId, relationId, Actor.user(userId));
  }

  public MemorySettings settings(UUID userId) {
    return store.settings(userId);
  }

  public MemorySettings updateSettings(UUID userId, MemorySettings settings) {
    return store.updateSettings(userId, settings);
  }

  public ContextPreview preview(UUID userId, String agentKey, String request) {
    if (request == null || request.isBlank()) throw new ValidationException("Give a request to preview");
    if (request.length() > MAX_REQUEST_CHARS) throw new ValidationException("That request is too long to preview");
    AgentDto agent = agentKey == null || agentKey.isBlank()
        ? agents.orchestrator(userId)
        : agents.byKey(userId, agentKey).orElseThrow(() -> new NotFoundException("Agent", agentKey));
    MemoryContextProvider.MemoryContext ctx = context.preview(userId, agent.id(), request);
    return new ContextPreview(agent, request, ctx.rendered(), ctx.memoryIds(), ctx.subjectIds());
  }
}
