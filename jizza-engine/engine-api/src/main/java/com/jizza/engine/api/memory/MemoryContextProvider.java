package com.jizza.engine.api.memory;

import java.util.List;
import java.util.UUID;

/**
 * Assembles what an agent should already know before it answers: the facts relevant to this
 * request that the agent is allowed to see, grouped by who or what they are about.
 *
 * <p>Called by the agent runtime on every turn, so no agent has to remember to search its memory,
 * and a specialist that was handed a task (and cannot see the conversation) still starts informed.
 */
public interface MemoryContextProvider {

  MemoryContext contextFor(UUID userId, UUID agentId, String request);

  /** The same context, for showing the user what an agent would be given; records no access. */
  default MemoryContext preview(UUID userId, UUID agentId, String request) {
    return contextFor(userId, agentId, request);
  }

  /**
   * @param rendered    the block to place in the agent's prompt; empty when nothing is relevant
   * @param memoryIds   the facts included, in rank order
   * @param subjectIds  the subjects the request was resolved to
   */
  record MemoryContext(String rendered, List<UUID> memoryIds, List<UUID> subjectIds) {

    public static final MemoryContext EMPTY = new MemoryContext("", List.of(), List.of());

    public boolean isEmpty() { return rendered == null || rendered.isBlank(); }
  }
}
