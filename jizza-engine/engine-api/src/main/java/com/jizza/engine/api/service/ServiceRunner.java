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

  /**
   * Polls one trigger now, records new items in history, marks them as seen and starts the
   * workflows listening to it.
   */
  TriggerSample pollTrigger(UUID serviceId, UUID triggerId);

  /**
   * One delivery to a webhook trigger's address: checked, recorded in history, and handed to the
   * workflows listening to it. The token in the address, and the signature when the trigger has a
   * secret, are what authenticate it; there is no session.
   *
   * @param rawBody the body exactly as received, since a signature is over the bytes
   * @param headers request headers with lower-case names
   */
  default ServiceModel.WebhookDelivery receiveWebhook(String token, String rawBody,
                                                      java.util.Map<String, String> headers) {
    return new ServiceModel.WebhookDelivery(false, "Instant triggers are not available", 0, 0);
  }
}
