package com.jizza.platform.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.common.audit.AuditLog;
import com.jizza.common.crypto.EncryptionService;
import com.jizza.common.dto.RunDto;
import com.jizza.common.enums.RunStatus;
import com.jizza.common.util.Json;
import com.jizza.common.workflow.WorkflowEngine;
import com.jizza.persistence.entity.InboundEventEntity;
import com.jizza.persistence.entity.WebhookSubscriptionEntity;
import com.jizza.persistence.repository.InboundEventRepository;
import com.jizza.persistence.repository.WebhookSubscriptionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WebhookServiceTest {

  private static final String SECRET = "shared-webhook-secret";

  private final UUID userId = UUID.randomUUID();
  private final UUID workflowId = UUID.randomUUID();
  private final List<WebhookSubscriptionEntity> subscriptionRows = new ArrayList<>();
  private final List<InboundEventEntity> eventRows = new ArrayList<>();

  private WorkflowEngine engine;
  private WebhookService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
    when(subscriptions.save(any())).thenAnswer(inv -> {
      WebhookSubscriptionEntity s = inv.getArgument(0);
      if (s.getId() == null) s.setId(UUID.randomUUID());
      subscriptionRows.removeIf(r -> r.getId().equals(s.getId()));
      subscriptionRows.add(s);
      return s;
    });
    when(subscriptions.findByEndpointToken(any())).thenAnswer(inv -> subscriptionRows.stream()
        .filter(s -> s.getEndpointToken().equals(inv.getArgument(0))).findFirst());

    InboundEventRepository events = mock(InboundEventRepository.class);
    when(events.save(any())).thenAnswer(inv -> {
      InboundEventEntity e = inv.getArgument(0);
      if (e.getId() == null) e.setId(UUID.randomUUID());
      eventRows.add(e);
      return e;
    });

    engine = mock(WorkflowEngine.class);
    when(engine.start(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
        .thenAnswer(inv -> new RunDto(UUID.randomUUID(), userId, inv.getArgument(0), 1, "webhook",
            RunStatus.DONE, null, null, Instant.now(), Instant.now(), List.of()));
    ObjectProvider<WorkflowEngine> engineProvider = mock(ObjectProvider.class);
    when(engineProvider.getIfAvailable()).thenReturn(engine);

    EncryptionService encryption = new EncryptionService() {
      @Override public byte[] encrypt(byte[] plain) { return plain; }
      @Override public byte[] decrypt(byte[] ct) { return ct; }
    };

    service = new WebhookService(subscriptions, events, encryption, engineProvider,
        mock(AuditLog.class));
  }

  private String sign(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) hex.append(String.format("%02x", b));
      return "sha256=" + hex;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private WebhookSubscriptionEntity subscribeSigned() {
    return service.subscribe(userId, UUID.randomUUID(), workflowId,
        Json.obj().put("type", "hmac_sha256").put("header", "X-Signature")
            .put("secret", SECRET).put("prefix", "sha256="));
  }

  @Test
  void mintsAnUnguessableEndpointToken() {
    var first = service.subscribe(userId, null, workflowId, null);
    var second = service.subscribe(userId, null, workflowId, null);

    assertThat(first.getEndpointToken()).isNotEqualTo(second.getEndpointToken())
        .hasSizeGreaterThanOrEqualTo(40).matches("[A-Za-z0-9_-]+");
  }

  @Test
  void aCorrectlySignedDeliveryFiresTheWorkflow() {
    var subscription = subscribeSigned();
    String body = "{\"event\":\"invoice.paid\",\"id\":\"inv_1\"}";

    var delivery = service.receive(subscription.getEndpointToken(), body,
        Map.of("x-signature", sign(body)));

    assertThat(delivery.accepted()).isTrue();
    assertThat(delivery.runId()).isNotNull();
    verify(engine).start(org.mockito.ArgumentMatchers.eq(workflowId),
        org.mockito.ArgumentMatchers.anyInt(), any(), org.mockito.ArgumentMatchers.startsWith("webhook:"));
  }

  @Test
  void aBadSignatureIsRecordedAndDroppedWithoutFiringAnything() {
    var subscription = subscribeSigned();

    var delivery = service.receive(subscription.getEndpointToken(), "{\"event\":\"spoofed\"}",
        Map.of("x-signature", "sha256=deadbeef"));

    assertThat(delivery.accepted()).isFalse();
    assertThat(delivery.reason()).contains("Signature verification failed");
    verify(engine, never()).start(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    assertThat(eventRows).hasSize(1);
    assertThat(eventRows.get(0).getVerified()).isFalse();
  }

  @Test
  void aMissingSignatureHeaderIsRefused() {
    var subscription = subscribeSigned();
    assertThat(service.receive(subscription.getEndpointToken(), "{}", Map.of()).accepted()).isFalse();
  }

  @Test
  void aSignatureOverDifferentBytesIsRefused() {
    var subscription = subscribeSigned();
    String signature = sign("{\"amount\":1}");

    var delivery = service.receive(subscription.getEndpointToken(), "{\"amount\":100000}",
        Map.of("x-signature", signature));

    assertThat(delivery.accepted()).isFalse();
  }

  @Test
  void anUnsupportedVerificationTypeIsRefusedRatherThanWavedThrough() {
    var subscription = service.subscribe(userId, null, workflowId,
        Json.obj().put("type", "magic"));

    assertThat(service.receive(subscription.getEndpointToken(), "{}", Map.of()).accepted()).isFalse();
  }

  @Test
  void withNoVerificationConfiguredThePathTokenIsTheCredential() {
    var subscription = service.subscribe(userId, null, workflowId, null);

    var delivery = service.receive(subscription.getEndpointToken(), "{\"ok\":true}", Map.of());

    assertThat(delivery.accepted()).isTrue();
  }

  @Test
  void aDisabledEndpointAcceptsNothing() {
    var subscription = service.subscribe(userId, null, workflowId, null);
    subscription.setEnabled(false);

    assertThat(service.receive(subscription.getEndpointToken(), "{}", Map.of()).accepted()).isFalse();
  }

  @Test
  void anUnparseableBodyIsStillRecordedRatherThanThrown() {
    var subscription = service.subscribe(userId, null, null, null);

    var delivery = service.receive(subscription.getEndpointToken(), "not json at all", Map.of());

    assertThat(delivery.accepted()).isTrue();
    assertThat(eventRows.get(0).getPayload().path("raw").asText()).isEqualTo("not json at all");
  }

  @Test
  void anUnknownEndpointIsNotFound() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        service.receive("never-issued", "{}", Map.of()))
        .isInstanceOf(com.jizza.common.error.NotFoundException.class);
  }
}
