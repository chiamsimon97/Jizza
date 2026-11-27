package com.jizza.engine.api.memory;

import com.jizza.engine.api.enums.ActorType;
import java.util.UUID;

/**
 * Who is changing memory, for history and the audit log.
 *
 * @param context the run the change is part of, as {@code kind:ref} (see
 *                {@link com.jizza.engine.api.cosmos.AccessContext}); null to take the run current on
 *                the thread, if any
 */
public record Actor(ActorType type, UUID id, String context) {

  public Actor(ActorType type, UUID id) {
    this(type, id, null);
  }

  public static Actor user(UUID userId) { return new Actor(ActorType.USER, userId); }

  public static Actor agent(UUID agentId) { return new Actor(ActorType.AGENT, agentId); }

  public static Actor system() { return new Actor(ActorType.SYSTEM, null); }

  /** The same actor, acting as part of the named run. */
  public Actor within(String runContext) { return new Actor(type, id, runContext); }

  /**
   * The run to record in history: the one named here, else the run on the thread, else none (the
   * user on a memory screen).
   */
  public String contextRef() {
    if (context != null && !context.isBlank()) return context;
    return com.jizza.engine.api.cosmos.AccessContext.current()
        .map(com.jizza.engine.api.cosmos.AccessContext::toTrace).orElse(null);
  }
}
