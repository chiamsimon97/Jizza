package com.jizza.engine.api.device;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.UUID;

/**
 * What a caller asks the broker to run. The caller has already judged it against the guardrails,
 * as callers of {@code McpServers.call} do.
 *
 * @param version        0 for the newest version the chosen device declared
 * @param timeout        null for the action's default
 * @param idempotencyKey the same key returns the command already made for it
 * @param interactive    a person is waiting (chat), so it goes ahead of queued background work
 */
public record DeviceCommandRequest(UUID userId, UUID workspaceId, DeviceTarget target,
                                   String action, int version, JsonNode input, Duration timeout,
                                   UUID runId, String nodeId, String idempotencyKey,
                                   boolean interactive) {}
