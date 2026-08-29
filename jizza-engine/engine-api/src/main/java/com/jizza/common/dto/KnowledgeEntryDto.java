package com.jizza.common.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.DataClassification;
import java.time.Instant;
import java.util.UUID;

public record KnowledgeEntryDto(UUID id, UUID agentId, String agentName, String title, String content,
                                JsonNode data, DataClassification classification,
                                Instant createdAt, Instant updatedAt) {}
