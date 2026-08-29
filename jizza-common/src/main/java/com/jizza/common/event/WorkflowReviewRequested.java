package com.jizza.common.event;

import java.util.UUID;

public record WorkflowReviewRequested(UUID userId, UUID notificationId, UUID sourceAgentId, String sourceAgentName, UUID workflowId, int version)
    implements BusEvent {}
