package com.jizza.common.knowledge;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

/** Namespace-scoped knowledge access. Every method takes the calling agent id and enforces it. */
public interface KnowledgeStore {

  List<KnowledgeEntry> search(UUID agentId, String query, int k);

  Optional<KnowledgeEntry> get(UUID agentId, UUID entryId);

  KnowledgeEntry upsert(UUID agentId, KnowledgeEntry e);

  void delete(UUID agentId, UUID entryId);

  List<KnowledgeEntry> list(UUID agentId, int limit);
}
