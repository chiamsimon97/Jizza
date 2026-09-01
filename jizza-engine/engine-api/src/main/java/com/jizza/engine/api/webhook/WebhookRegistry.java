package com.jizza.engine.api.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.WebhookSubscriptionDto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Inbound webhook endpoints: subscribe, list, unsubscribe, and verify a delivery (R44). */
public interface WebhookRegistry {

  WebhookSubscriptionDto subscribe(UUID userId, UUID connectionId, UUID workflowId,
                                   JsonNode verifyConfig);

  List<WebhookSubscriptionDto> list(UUID userId);

  Optional<WebhookSubscriptionDto> find(UUID subscriptionId);

  void unsubscribe(UUID subscriptionId);

  /**
   * @param rawBody the body exactly as received; a signature is over the bytes, so it cannot be
   *                recomputed from a re-serialized object
   */
  Delivery receive(String endpointToken, String rawBody, Map<String, String> headers);

  record Delivery(boolean accepted, String reason, UUID runId) {}
}
