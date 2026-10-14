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
}
