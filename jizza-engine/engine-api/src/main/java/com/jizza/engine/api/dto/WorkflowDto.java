package com.jizza.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;

public record WorkflowDto(UUID id, UUID userId, String name, String description, JsonNode graph,
                          JsonNode inputSchema, JsonNode triggerConfig, WorkflowOrigin origin,
                          WorkflowStatus status, UUID owningAgentId, int version,
                          UUID sourceTaskId, Instant createdAt) {}
