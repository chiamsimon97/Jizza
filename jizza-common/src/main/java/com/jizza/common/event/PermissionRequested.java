package com.jizza.common.event;

import java.util.UUID;

public record PermissionRequested(UUID userId, UUID notificationId, UUID sourceAgentId, String sourceAgentName, String body, String toolRef)
    implements BusEvent {}
