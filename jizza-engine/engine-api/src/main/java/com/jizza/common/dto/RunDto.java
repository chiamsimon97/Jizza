package com.jizza.common.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.RunStatus;
import com.jizza.common.enums.RunStepStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RunDto(UUID id, UUID userId, UUID workflowId, int version, String triggerSource,
                     RunStatus status, String currentNode, JsonNode context,
                     Instant createdAt, Instant updatedAt, List<StepDto> steps) {

  public record StepDto(UUID id, String nodeId, RunStepStatus status, JsonNode output,
                        int attempts, Instant finishedAt) {}
}
