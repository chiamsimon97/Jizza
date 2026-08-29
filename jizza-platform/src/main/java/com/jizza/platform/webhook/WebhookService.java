package com.jizza.platform.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.audit.AuditLog;
import com.jizza.common.crypto.EncryptionService;
import com.jizza.common.enums.ActorType;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.util.Json;
import com.jizza.common.workflow.WorkflowEngine;
import com.jizza.persistence.entity.InboundEventEntity;
import com.jizza.persistence.entity.WebhookSubscriptionEntity;
import com.jizza.persistence.repository.InboundEventRepository;
import com.jizza.persistence.repository.WebhookSubscriptionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verified inbound webhooks feeding the event/webhook workflow trigger (R44).
 *
 * <p>Two layers of authentication, because this is the one endpoint reachable without a session:
 * an unguessable per-subscription token in the path, and -- where the connector declares one -- an
 * HMAC signature over the raw body, compared in constant time. An unverified delivery is recorded
 * and dropped rather than triggering anything.
 */
@Service
public class WebhookService {

  private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

  private final WebhookSubscriptionRepository subscriptions;
  private final InboundEventRepository events;
  private final EncryptionService encryption;
  private final ObjectProvider<WorkflowEngine> workflowEngine;
  private final AuditLog audit;
  private final SecureRandom random = new SecureRandom();

  public WebhookService(WebhookSubscriptionRepository subscriptions, InboundEventRepository events,
                        EncryptionService encryption, ObjectProvider<WorkflowEngine> workflowEngine,
                        AuditLog audit) {
    this.subscriptions = subscriptions;
    this.events = events;
    this.workflowEngine = workflowEngine;
    this.encryption = encryption;
    this.audit = audit;
  }

  public record Delivery(boolean accepted, String reason, UUID runId) {}

  @Transactional
  public WebhookSubscriptionEntity subscribe(UUID userId, UUID connectionId, UUID workflowId,
                                             JsonNode verifyConfig) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);

    WebhookSubscriptionEntity entity = new WebhookSubscriptionEntity();
    entity.setUserId(userId);
    entity.setConnectionId(connectionId);
    entity.setWorkflowId(workflowId);
    entity.setEndpointToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    entity.setVerifyConfig(verifyConfig);
    entity.setEnabled(true);
    subscriptions.save(entity);

    audit.record(userId, ActorType.USER, null, "webhook.subscribed",
        workflowId == null ? null : workflowId.toString(), null);
    return entity;
  }

  @Transactional(readOnly = true)
  public List<WebhookSubscriptionEntity> list(UUID userId) {
    return subscriptions.findByUserId(userId);
  }

  @Transactional
  public void unsubscribe(UUID subscriptionId) {
    subscriptions.findById(subscriptionId).ifPresent(entity -> {
      entity.setEnabled(false);
      subscriptions.save(entity);
      audit.record(entity.getUserId(), ActorType.USER, null, "webhook.unsubscribed",
          subscriptionId.toString(), null);
    });
  }

  /**
   * @param rawBody the body exactly as received; a signature is over the bytes, so it cannot be
   *                recomputed from a re-serialized object
   */
  @Transactional
  public Delivery receive(String endpointToken, String rawBody, Map<String, String> headers) {
    WebhookSubscriptionEntity subscription = subscriptions.findByEndpointToken(endpointToken)
        .orElseThrow(() -> new NotFoundException("Webhook endpoint", endpointToken));
    if (!Boolean.TRUE.equals(subscription.getEnabled())) {
      return new Delivery(false, "This endpoint is disabled", null);
    }

    boolean verified = verify(subscription, rawBody, headers);

    InboundEventEntity event = new InboundEventEntity();
    event.setSubscriptionId(subscription.getId());
    event.setPayload(safeJson(rawBody));
    event.setVerified(verified);
    events.save(event);

    if (!verified) {
      log.warn("Rejected an unverified delivery on webhook {}", subscription.getId());
      audit.record(subscription.getUserId(), ActorType.SYSTEM, null, "webhook.rejected",
          subscription.getId().toString(), null);
      return new Delivery(false, "Signature verification failed", null);
    }

    audit.record(subscription.getUserId(), ActorType.SYSTEM, null, "webhook.received",
        subscription.getId().toString(),
        Json.obj().put("eventId", event.getId().toString()));

    if (subscription.getWorkflowId() == null) {
      return new Delivery(true, "Recorded; no workflow is attached to this endpoint", null);
    }
    WorkflowEngine engine = workflowEngine.getIfAvailable();
    if (engine == null) {
      return new Delivery(true, "Recorded; the workflow engine is unavailable", null);
    }

    var workflowVersion = subscription.getVerifyConfig() == null
        ? 1 : subscription.getVerifyConfig().path("version").asInt(1);
    var run = engine.start(subscription.getWorkflowId(), workflowVersion,
        Json.obj().set("event", event.getPayload()), "webhook:" + subscription.getId());
    return new Delivery(true, "Accepted", run.id());
  }

  /**
   * Verifies against the connector's declared config, for example
   * {@code {"type":"hmac_sha256","header":"X-Signature","secret":"...","prefix":"sha256="}}.
   * With no config the path token alone is the credential.
   */
  private boolean verify(WebhookSubscriptionEntity subscription, String rawBody,
                         Map<String, String> headers) {
    JsonNode config = subscription.getVerifyConfig();
    if (config == null || config.isNull() || !config.hasNonNull("type")) return true;

    String type = config.path("type").asText().toLowerCase(Locale.ROOT);
    if (!"hmac_sha256".equals(type)) {
      log.warn("Unsupported webhook verification type '{}'; refusing the delivery", type);
      return false;
    }

    String headerName = config.path("header").asText("X-Signature").toLowerCase(Locale.ROOT);
    String provided = headers.getOrDefault(headerName, null);
    if (provided == null) return false;

    String secret = config.hasNonNull("secretEncrypted")
        ? encryption.decryptString(Base64.getDecoder().decode(config.get("secretEncrypted").asText()))
        : config.path("secret").asText(null);
    if (secret == null) return false;

    String expected = config.path("prefix").asText("") + hmacHex(secret, rawBody);
    return constantTimeEquals(expected, provided.trim());
  }

  private String hmacHex(String secret, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  private boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8));
  }

  /** A webhook body is untrusted; store it as JSON where possible and as text otherwise. */
  private JsonNode safeJson(String rawBody) {
    try {
      return rawBody == null || rawBody.isBlank() ? Json.obj() : Json.read(rawBody);
    } catch (RuntimeException e) {
      return Json.obj().put("raw", rawBody);
    }
  }
}
