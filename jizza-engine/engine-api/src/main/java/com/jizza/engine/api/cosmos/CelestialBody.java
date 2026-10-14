package com.jizza.engine.api.cosmos;

import java.time.Instant;
import java.util.Map;

/**
 * One body in the Memory Cosmos: the sun, a planet, or something orbiting one.
 *
 * @param bodyId       stable {@code "TYPE:uuid"} (a connector, which several agents can share, adds
 *                     {@code ":agentId"}); the client hashes it for a layout that never moves
 * @param agentKey     the agent this body belongs to, for its colour; never a colour itself
 * @param memoryCount  memory items orbiting this body directly
 * @param sharedCount  of those, facts more than one agent can see
 * @param meta         display details only: a connector carries its service name and status, never
 *                     a credential, URL or configuration value
 */
public record CelestialBody(String bodyId, BodyType type, String parentBodyId, String label,
                            String agentKey, int tier, long memoryCount, long sharedCount,
                            Instant lastActivityAt, Map<String, Object> meta) {

  public CelestialBody withCounts(long memories, long shared, Instant lastActivity) {
    return new CelestialBody(bodyId, type, parentBodyId, label, agentKey, tier, memories, shared,
        lastActivity, meta);
  }

  public CelestialBody under(String parent, int newTier) {
    return new CelestialBody(bodyId, type, parent, label, agentKey, newTier, memoryCount,
        sharedCount, lastActivityAt, meta);
  }
}
