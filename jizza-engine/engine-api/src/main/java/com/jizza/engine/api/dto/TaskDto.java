package com.jizza.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ScheduleType;
import com.jizza.engine.api.enums.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public record TaskDto(UUID id, UUID userId, UUID agentId, String agentName, String title,
                      JsonNode payload, ScheduleType scheduleType, String cronOrRrule,
                      Instant runAt, Instant nextRunAt, String timezone,
                      UUID boundWorkflowId, Integer boundVersion, TaskStatus status,
                      Instant lastRunAt, String lastResult, boolean repetitive) {

  /** True when this task is bound to an approved procedure and must follow it exactly (R36). */
  public boolean followsProcedure() { return boundWorkflowId != null; }
}
