package com.jizza.engine.api.event;

import java.util.UUID;

public record InstructionRequired(UUID userId, UUID notificationId, UUID sourceAgentId, String sourceAgentName, UUID runId, String body)
    implements BusEvent {}
