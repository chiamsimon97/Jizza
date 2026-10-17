package com.jizza.engine.api.cosmos;

import java.util.List;
import java.util.UUID;

/**
 * Contributes the bodies that orbit one agent from the module that owns them (connectors,
 * workflows and skills), so the Cosmos never reaches into another module's tables.
 *
 * <p>Each returned body's {@code parentBodyId} is the agent's {@code bodyId}, or another body this
 * provider returned (a skill built from a workflow orbits that workflow).
 */
public interface CosmosBodyProvider {

  List<CelestialBody> bodies(UUID userId, CosmosAgent agent);
}
