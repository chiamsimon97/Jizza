package com.jizza.engine.api.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Files in an agent's memory, namespaced exactly like {@link KnowledgeStore}: every method takes
 * the agent id, and a file is only ever reachable through the agent that holds it.
 *
 * <p>Adding a file also indexes whatever text can be read from it as a knowledge entry in the same
 * namespace, so the agent can find it with its ordinary knowledge search. Deleting the file removes
 * that entry too.
 */
public interface MemoryFileStore {

  List<MemoryFile> list(UUID agentId);

  MemoryFile add(UUID agentId, String filename, String contentType, byte[] bytes);

  Optional<MemoryFile.Content> content(UUID agentId, UUID fileId);

  void delete(UUID agentId, UUID fileId);
}
