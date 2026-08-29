package com.jizza.common.event;

import java.util.UUID;

public record InfoRaised(UUID userId, UUID notificationId, UUID sourceAgentId, String sourceAgentName, String body)
    implements BusEvent {}
