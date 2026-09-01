package com.jizza.platform.usecase.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.WebhookSubscriptionDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.webhook.WebhookRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscribing and unsubscribing an inbound webhook endpoint (R44).
 *
 * <p>Verifying and dispatching a delivery is security mechanism and stays behind
 * {@link WebhookRegistry} itself; nothing about who is allowed to receive a signed request changes
 * from one caller to the next. Managing the subscription is ordinary product surface, and it is
 * where an ownership check belongs: {@code unsubscribe} previously had none, so any authenticated
 * user who guessed or enumerated a subscription id could disable somebody else's endpoint. This
 * class closes that gap the same way every other owned resource in the platform is guarded.
 */
@Service
public class WebhookUseCase {

  private final WebhookRegistry webhooks;
  private final AuditLog audit;

  public WebhookUseCase(WebhookRegistry webhooks, AuditLog audit) {
    this.webhooks = webhooks;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<WebhookSubscriptionDto> list(UUID userId) {
    return webhooks.list(userId);
  }

  @Transactional
  public WebhookSubscriptionDto subscribe(UUID userId, UUID connectionId, UUID workflowId,
                                          JsonNode verifyConfig) {
    WebhookSubscriptionDto subscription = webhooks.subscribe(userId, connectionId, workflowId,
        verifyConfig);
    audit.record(userId, ActorType.USER, null, "webhook.subscribed",
        workflowId == null ? null : workflowId.toString(), null);
    return subscription;
  }

  @Transactional
  public void unsubscribe(UUID userId, UUID subscriptionId) {
    ownedBy(userId, subscriptionId);
    webhooks.unsubscribe(subscriptionId);
    audit.record(userId, ActorType.USER, null, "webhook.unsubscribed", subscriptionId.toString(),
        null);
  }

  private WebhookSubscriptionDto ownedBy(UUID userId, UUID subscriptionId) {
    return webhooks.find(subscriptionId)
        .filter(subscription -> subscription.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Webhook endpoint", subscriptionId));
  }
}
