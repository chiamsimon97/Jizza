package com.jizza.engine.api.orchestrator;

import java.time.Instant;
import java.util.UUID;

/** One saved version of what a custom agent is called and told. */
public record AgentRevision(UUID id, UUID agentId, int revision, String name, String description,
                            String systemPrompt, String modelTier, UUID changedByUserId,
                            Instant createdAt) {}
