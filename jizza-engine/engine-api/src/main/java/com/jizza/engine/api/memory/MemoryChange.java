package com.jizza.engine.api.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActorType;
import java.time.Instant;
import java.util.UUID;

/** One entry in a memory's history: what it said before a change, and who changed it. */
public record MemoryChange(UUID id, UUID memoryId, String change, String previousContent,
                           JsonNode previousData, ActorType actorType, UUID actorId, String note,
                           Instant at) {}
