package com.jizza.engine.connectors.tools.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.connector.ToolExecutor;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.service.ServiceModel;
import com.jizza.engine.api.service.ServiceTriggerListeners;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.ServiceTriggerEntity;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.ServiceActionRepository;
import com.jizza.engine.persistence.repository.ServiceRunRepository;
import com.jizza.engine.persistence.repository.ServiceTriggerRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** Instant triggers: the service calls Jizza, and each new item starts the listening workflows. */
class ServiceWebhookTest {

  private final UUID ownerId = UUID.randomUUID();
  private ServiceTriggerEntity trigger;
  private ConnectionEntity service;
  private ServiceTriggerListeners listeners;
  private DefaultServiceRunner runner;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    service = new ConnectionEntity();
    service.setId(UUID.randomUUID());
    service.setUserId(ownerId);
    service.setServiceName("School portal");
    service.setVersionStatus(ServiceModel.LIVE);

    trigger = new ServiceTriggerEntity();
    trigger.setId(UUID.randomUUID());
    trigger.setConnectionId(service.getId());
    trigger.setName("homework_set");
    trigger.setLabel("Homework set");
    trigger.setKind(ServiceModel.WEBHOOK);
    trigger.setEndpointToken("tok");
    trigger.setIdField("id");
    trigger.setEnabled(true);
    trigger.setSeenIds(Json.mapper().createArrayNode());

    ServiceTriggerRepository triggers = mock(ServiceTriggerRepository.class);
    when(triggers.findByEndpointToken("tok")).thenReturn(Optional.of(trigger));
    when(triggers.findById(trigger.getId())).thenReturn(Optional.of(trigger));
    ConnectionRepository services = mock(ConnectionRepository.class);
    when(services.findById(service.getId())).thenReturn(Optional.of(service));
    EncryptionService encryption = mock(EncryptionService.class);
    when(encryption.decryptString(any())).thenAnswer(
        i -> new String((byte[]) i.getArgument(0), StandardCharsets.UTF_8));
    listeners = mock(ServiceTriggerListeners.class);
    when(listeners.start(any(), any(), anyList(), any())).thenAnswer(
        i -> ((List<?>) i.getArgument(2)).size());
    ObjectProvider<ServiceTriggerListeners> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(listeners);

    runner = new DefaultServiceRunner(mock(ServiceActionRepository.class), triggers,
        mock(GeneratedToolRepository.class), mock(ServiceRunRepository.class),
        mock(ToolExecutor.class));
    runner.setTriggerSupport(services, encryption, provider);
  }

  @Test
  @SuppressWarnings("unchecked")
  void anEventStartsTheWorkflowsOncePerNewItem() {
    var first = runner.receiveWebhook("tok", "[{\"id\":1},{\"id\":2}]", Map.of());
    assertThat(first.accepted()).isTrue();
    assertThat(first.items()).isEqualTo(2);
    assertThat(first.runs()).isEqualTo(2);

    // The service sends item 2 again: only item 3 is new.
    var again = runner.receiveWebhook("tok", "[{\"id\":2},{\"id\":3}]", Map.of());
    assertThat(again.items()).isEqualTo(1);
    ArgumentCaptor<List<JsonNode>> items = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<JsonNode> about = ArgumentCaptor.forClass(JsonNode.class);
    verify(listeners, org.mockito.Mockito.times(2)).start(eq(trigger.getId()), eq(ownerId),
        items.capture(), about.capture());
    assertThat(items.getValue()).extracting(i -> i.path("id").asInt()).containsExactly(3);
    assertThat(about.getValue().path("source").asText()).isEqualTo("webhook");
    assertThat(trigger.getLastReceivedAt()).isNotNull();
  }

  @Test
  void aSingleObjectIsOneItemAndTextIsKept() {
    assertThat(runner.receiveWebhook("tok", "{\"subject\":\"Maths\"}", Map.of()).items())
        .isEqualTo(1);
    assertThat(runner.receiveWebhook("tok", "plain words", Map.of()).items()).isEqualTo(1);
  }

  @Test
  void aSignedTriggerRefusesABadSignature() {
    trigger.setSigningSecret("s3cret-key".getBytes(StandardCharsets.UTF_8));
    trigger.setSignatureHeader("X-Hub-Signature-256");
    trigger.setSignaturePrefix("sha256=");
    String body = "{\"id\":9}";

    var refused = runner.receiveWebhook("tok", body, Map.of("x-hub-signature-256", "sha256=00"));
    assertThat(refused.accepted()).isFalse();
    assertThat(refused.reason()).contains("signature");

    String good = "sha256=" + DefaultServiceRunner.hmacHex("s3cret-key", body);
    assertThat(runner.receiveWebhook("tok", body, Map.of("x-hub-signature-256", good)).accepted())
        .isTrue();
  }

  @Test
  void anOffTriggerOrDraftServiceStartsNothing() {
    trigger.setEnabled(false);
    assertThat(runner.receiveWebhook("tok", "{}", Map.of()).accepted()).isFalse();
    trigger.setEnabled(true);
    service.setVersionStatus("draft");
    assertThat(runner.receiveWebhook("tok", "{}", Map.of()).accepted()).isFalse();
    verify(listeners, never()).start(any(), any(), anyList(), any());
  }

  @Test
  void anUnknownAddressIsNotFound() {
    assertThatThrownBy(() -> runner.receiveWebhook("nope", "{}", Map.of()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aWebhookTriggerIsNotPolled() {
    var sample = runner.pollTrigger(service.getId(), trigger.getId());
    assertThat(sample.ok()).isFalse();
  }
}
