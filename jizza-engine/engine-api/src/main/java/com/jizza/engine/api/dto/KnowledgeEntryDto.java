package com.jizza.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.DataClassification;
import java.time.Instant;
import java.util.UUID;

public record KnowledgeEntryDto(UUID id, UUID agentId, String agentName, String title, String content,
                                JsonNode data, DataClassification classification,
                                Instant createdAt, Instant updatedAt) {}
