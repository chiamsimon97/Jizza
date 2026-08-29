package com.jizza.common.event;

import java.util.UUID;

/** Marker for the notification-bus event family published on the application event bus. */
public sealed interface BusEvent
    permits InfoRaised, PermissionRequested, WorkflowReviewRequested, InstructionRequired,
            PermissionAnswered, ReviewAnswered, InstructionSubmitted {

  UUID userId();

  UUID notificationId();
}
