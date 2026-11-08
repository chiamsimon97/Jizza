package com.jizza.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * @param reuse what the workflow library keeps for reusing it, or null when it is not in it
 * @param enabled whether it may start from its triggers (a form submission, a schedule)
 * @param formId the form whose submissions start it, or null
 */
public record WorkflowDto(UUID id, UUID userId, String name, String description, JsonNode graph,
                          JsonNode inputSchema, JsonNode triggerConfig, WorkflowOrigin origin,
                          WorkflowStatus status, UUID owningAgentId, int version,
                          UUID sourceTaskId, Instant createdAt, JsonNode reuse,
                          boolean enabled, UUID formId) {

  public WorkflowDto(UUID id, UUID userId, String name, String description, JsonNode graph,
                     JsonNode inputSchema, JsonNode triggerConfig, WorkflowOrigin origin,
                     WorkflowStatus status, UUID owningAgentId, int version, UUID sourceTaskId,
                     Instant createdAt, JsonNode reuse) {
    this(id, userId, name, description, graph, inputSchema, triggerConfig, origin, status,
        owningAgentId, version, sourceTaskId, createdAt, reuse, status == WorkflowStatus.APPROVED,
        null);
  }

  public WorkflowDto(UUID id, UUID userId, String name, String description, JsonNode graph,
                     JsonNode inputSchema, JsonNode triggerConfig, WorkflowOrigin origin,
                     WorkflowStatus status, UUID owningAgentId, int version, UUID sourceTaskId,
                     Instant createdAt) {
    this(id, userId, name, description, graph, inputSchema, triggerConfig, origin, status,
        owningAgentId, version, sourceTaskId, createdAt, null);
  }
}
