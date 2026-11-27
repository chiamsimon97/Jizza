package com.jizza.engine.api.knowledge;

import com.jizza.engine.api.memory.Actor;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

/**
 * Namespace-scoped knowledge access. Every method takes the calling agent id and enforces it.
 *
 * <p>{@code search} and {@code get} are an agent reading its own knowledge: they never return a
 * restricted entry, and each read is stamped in the entry's passport. {@code list} and the
 * user-initiated changes below serve the user's memory screens, after the caller has checked the
 * user owns the agents involved.
 */
public interface KnowledgeStore {

  List<KnowledgeEntry> search(UUID agentId, String query, int k);

  Optional<KnowledgeEntry> get(UUID agentId, UUID entryId);

  KnowledgeEntry upsert(UUID agentId, KnowledgeEntry e);

  void delete(UUID agentId, UUID entryId);

  List<KnowledgeEntry> list(UUID agentId, int limit);

  /** The user rewording one of an agent's notes. Keeps the previous wording in its history. */
  KnowledgeEntry edit(UUID agentId, UUID entryId, String title, String content, Actor actor);

  /**
   * The user handing a note to another agent of theirs: it leaves {@code fromAgentId}'s namespace
   * entirely (and so does the file it came from). The one way a note crosses namespaces; notes are
   * never shared.
   */
  KnowledgeEntry move(UUID fromAgentId, UUID entryId, UUID toAgentId, Actor actor);

  KnowledgeEntry restrict(UUID agentId, UUID entryId, boolean restricted, Actor actor);

  /**
   * The user teaching an agent a lesson (usually by approving a memory-review suggestion). A
   * lesson lives in the agent's own namespace like a note, but only the user writes one: an agent
   * cannot create or change it. A lesson with the same subject and wording is reinforced instead
   * of copied.
   */
  default KnowledgeEntry teach(UUID agentId, KnowledgeEntry.Lesson about, String content,
                               com.jizza.engine.api.memory.Actor actor) {
    throw new UnsupportedOperationException("Lessons are not supported by this knowledge store");
  }

  /** The agent's lessons it may use (restricted ones left out), most recently learned first. */
  default List<KnowledgeEntry> lessons(UUID agentId) {
    return List.of();
  }

  /** Titles of the agent's own notes it may open (no lessons, nothing restricted), newest first. */
  default List<KnowledgeEntry> noteIndex(UUID agentId, int limit) {
    return List.of();
  }
}
