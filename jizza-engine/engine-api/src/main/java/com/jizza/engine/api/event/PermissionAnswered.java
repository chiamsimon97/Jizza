package com.jizza.engine.api.event;

import java.util.UUID;

public record PermissionAnswered(UUID userId, UUID notificationId, boolean approved) implements BusEvent {}
