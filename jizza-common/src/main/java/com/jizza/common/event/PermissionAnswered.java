package com.jizza.common.event;

import java.util.UUID;

public record PermissionAnswered(UUID userId, UUID notificationId, boolean approved) implements BusEvent {}
