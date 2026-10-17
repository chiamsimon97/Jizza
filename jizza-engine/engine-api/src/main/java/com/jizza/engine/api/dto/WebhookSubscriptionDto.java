package com.jizza.engine.api.dto;

import java.util.UUID;

/** An inbound webhook endpoint bound to a connection and, optionally, a workflow to trigger (R44). */
public record WebhookSubscriptionDto(UUID id, UUID userId, UUID connectionId, UUID workflowId,
                                     String endpointToken, boolean enabled) {}
