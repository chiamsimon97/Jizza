package com.jizza.common.event;

import java.util.UUID;

public record InstructionSubmitted(UUID userId, UUID notificationId, UUID runId,
                                   String instruction) implements BusEvent {}
