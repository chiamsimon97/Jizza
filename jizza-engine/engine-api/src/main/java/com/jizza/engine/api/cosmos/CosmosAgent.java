package com.jizza.engine.api.cosmos;

import java.util.UUID;

/** An agent as the Cosmos sees it, handed to each {@link CosmosBodyProvider}. */
public record CosmosAgent(UUID id, String key, String name, String domain, boolean orchestrator,
                          String bodyId) {}
