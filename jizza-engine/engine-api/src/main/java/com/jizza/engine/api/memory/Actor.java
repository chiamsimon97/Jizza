package com.jizza.engine.api.memory;

import com.jizza.engine.api.enums.ActorType;
import java.util.UUID;

/** Who is changing memory, for history and the audit log. */
public record Actor(ActorType type, UUID id) {

  public static Actor user(UUID userId) { return new Actor(ActorType.USER, userId); }

  public static Actor agent(UUID agentId) { return new Actor(ActorType.AGENT, agentId); }

  public static Actor system() { return new Actor(ActorType.SYSTEM, null); }
}
