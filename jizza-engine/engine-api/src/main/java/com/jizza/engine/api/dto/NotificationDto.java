package com.jizza.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.EscalationState;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationPriority;
import com.jizza.engine.api.enums.NotificationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One item in the user's inbox.
 *
 * @param archivedAt   set once the user filed it away
 * @param snoozedUntil hidden from the inbox until then
 * @param responseNote what the user said when declining or asking for changes
 */
public record NotificationDto(UUID id, UUID userId, UUID sourceAgentId, String sourceAgentName,
                              NotificationKind kind, String body, JsonNode context,
                              UUID taskId, UUID workflowId, UUID runId,
                              NotificationStatus status, String submittedInstruction,
                              Instant expiresAt, EscalationState escalationState,
                              Instant createdAt, Instant answeredAt,
                              NotificationPriority priority, Instant archivedAt,
                              Instant snoozedUntil, String responseNote) {

  public NotificationDto(UUID id, UUID userId, UUID sourceAgentId, String sourceAgentName,
                         NotificationKind kind, String body, JsonNode context, UUID taskId,
                         UUID workflowId, UUID runId, NotificationStatus status,
                         String submittedInstruction, Instant expiresAt,
                         EscalationState escalationState, Instant createdAt, Instant answeredAt,
                         NotificationPriority priority) {
    this(id, userId, sourceAgentId, sourceAgentName, kind, body, context, taskId, workflowId, runId,
        status, submittedInstruction, expiresAt, escalationState, createdAt, answeredAt, priority,
        null, null, null);
  }

  public NotificationDto(UUID id, UUID userId, UUID sourceAgentId, String sourceAgentName,
                         NotificationKind kind, String body, JsonNode context, UUID taskId,
                         UUID workflowId, UUID runId, NotificationStatus status,
                         String submittedInstruction, Instant expiresAt,
                         EscalationState escalationState, Instant createdAt, Instant answeredAt) {
    this(id, userId, sourceAgentId, sourceAgentName, kind, body, context, taskId, workflowId, runId,
        status, submittedInstruction, expiresAt, escalationState, createdAt, answeredAt,
        NotificationPriority.NORMAL);
  }

  /** Still waiting on the user: a blocking item nobody has answered and that has not expired. */
  public boolean open() {
    return kind.blocking()
        && (status == NotificationStatus.UNREAD || status == NotificationStatus.READ);
  }
}
