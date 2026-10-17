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
   * @param items       the same facts with why each was picked, for showing the user what an agent
   *                    was given; may be empty when the provider does not say
   */
  record MemoryContext(String rendered, List<UUID> memoryIds, List<UUID> subjectIds, List<Item> items) {

    public static final MemoryContext EMPTY = new MemoryContext("", List.of(), List.of());

    public MemoryContext(String rendered, List<UUID> memoryIds, List<UUID> subjectIds) {
      this(rendered, memoryIds, subjectIds, List.of());
    }

    public MemoryContext {
      items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean isEmpty() { return rendered == null || rendered.isBlank(); }
  }

  /**
   * @param reason {@code core} (always included), {@code subject} (about who or what the request
   *               names), {@code related} (about something linked to it), {@code meaning} or
   *               {@code keywords}
   */
  record Item(UUID id, String text, String category, String reason, double score, boolean confirmed) {}
}
