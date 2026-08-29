package com.jizza.common.event;

import java.util.UUID;

public record ReviewAnswered(UUID userId, UUID notificationId, UUID workflowId,
                             boolean approved, String feedback) implements BusEvent {}
