package com.jizza.engine.api.cosmos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything about one memory item: who can see it, where it came from, and every stamp.
 *
 * @param content the whole text; null when restricted (fetch it with {@code reveal})
 */
public record Passport(MemoryNode item, String title, String content, Visibility visibility,
                       Origin origin, List<Stamp> stamps, String nextCursor) {

  public record AgentRef(java.util.UUID id, String key, String name) {}

  /**
   * @param everyAgent a shared fact: every agent sees it
   * @param domains    the domains a fact is shared with
   * @param agents     the agents that can see it now (a note: its one owner)
   */
  public record Visibility(boolean everyAgent, List<String> domains, List<AgentRef> agents) {}

  /**
   * @param kind {@code user}, {@code agent}, {@code conversation}, {@code import}, {@code note}
   *             (an agent's own note) or {@code file}
   */
  public record Origin(String kind, String label, String ref, UUID conversationId, UUID fileId,
                       UUID agentId) {}

  /**
   * @param action created, updated, confirmed, superseded, retired, restored, reinforced, shared,
   *               unshared, moved, restricted, unrestricted, or read
   */
  public record Stamp(String action, String actorType, UUID actorId, String actorLabel,
                      String contextKind, String contextRef, Instant at, String note) {}
}
