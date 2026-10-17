package com.jizza.engine.api.event;

import java.util.UUID;

public record InstructionSubmitted(UUID userId, UUID notificationId, UUID runId,
                                   String instruction) implements BusEvent {}
