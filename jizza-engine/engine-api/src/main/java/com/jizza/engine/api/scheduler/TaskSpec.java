package com.jizza.engine.api.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ScheduleType;
import java.time.Instant;
import java.util.UUID;

public record TaskSpec(UUID userId, UUID agentId, String title, JsonNode payload,
                       ScheduleType scheduleType, String cronOrRrule, Instant runAt,
                       String timezone, boolean repetitive) {

  public static TaskSpec oneTime(UUID userId, UUID agentId, String title, JsonNode payload, Instant at, String tz) {
    return new TaskSpec(userId, agentId, title, payload, ScheduleType.ONE_TIME, null, at, tz, false);
  }

  public static TaskSpec recurring(UUID userId, UUID agentId, String title, JsonNode payload,
                                   String cronOrRrule, String tz, boolean repetitive) {
    return new TaskSpec(userId, agentId, title, payload, ScheduleType.RECURRING, cronOrRrule, null, tz, repetitive);
  }
}
