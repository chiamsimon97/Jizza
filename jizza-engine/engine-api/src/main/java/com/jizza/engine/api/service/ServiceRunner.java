package com.jizza.engine.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.connector.IdempotencyKey;
import com.jizza.engine.api.service.ServiceModel.TriggerSample;
import com.jizza.engine.api.tool.ToolResult;
import java.util.UUID;

/**
 * Runs a service's actions and triggers through the connector executor, so credentials, the egress
 * guard and idempotency apply exactly as they do to any other tool. Every run lands in the
 * service's history.
 */
public interface ServiceRunner {

  /** {@code source} says who ran it: {@code agent}, {@code test} or {@code workflow}. */
  ToolResult runAction(UUID serviceId, UUID actionId, JsonNode args, String source,
                       IdempotencyKey key);

  /** Calls a trigger's API once and reports what it found, without marking anything as seen. */
  TriggerSample testTrigger(UUID serviceId, UUID triggerId);

  /** Polls one trigger now, records new items in history and marks them as seen. */
  TriggerSample pollTrigger(UUID serviceId, UUID triggerId);
}
