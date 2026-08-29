package com.jizza.common.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.EscalationState;
import com.jizza.common.enums.NotificationKind;
import com.jizza.common.enums.NotificationStatus;
import java.time.Instant;
import java.util.UUID;

public record NotificationDto(UUID id, UUID userId, UUID sourceAgentId, String sourceAgentName,
                              NotificationKind kind, String body, JsonNode context,
                              UUID taskId, UUID workflowId, UUID runId,
                              NotificationStatus status, String submittedInstruction,
                              Instant expiresAt, EscalationState escalationState,
                              Instant createdAt, Instant answeredAt) {}
