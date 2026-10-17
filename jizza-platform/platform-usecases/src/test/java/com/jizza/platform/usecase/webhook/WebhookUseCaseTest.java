package com.jizza.platform.usecase.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.WebhookSubscriptionDto;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.webhook.WebhookRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Subscribing and unsubscribing: ownership is checked, both actions are audited. */
class WebhookUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID subscriptionId = UUID.randomUUID();
  private WebhookRegistry webhooks;
  private AuditLog audit;
  private WebhookUseCase useCase;

  private WebhookSubscriptionDto dto() {
    return new WebhookSubscriptionDto(subscriptionId, userId, null, null, "token-abc", true);
  }

  @BeforeEach
  void setUp() {
    webhooks = mock(WebhookRegistry.class);
    when(webhooks.find(subscriptionId)).thenReturn(Optional.of(dto()));
    when(webhooks.subscribe(any(), any(), any(), any())).thenReturn(dto());
    audit = mock(AuditLog.class);
    useCase = new WebhookUseCase(webhooks, audit);
  }

  @Test
  void subscribingDelegatesAndAudits() {
    useCase.subscribe(userId, null, null, null);

    verify(webhooks).subscribe(userId, null, null, null);
    verify(audit).record(eq(userId), any(), any(), eq("webhook.subscribed"), any(), any());
  }

  @Test
  void unsubscribingChecksOwnershipDelegatesAndAudits() {
    useCase.unsubscribe(userId, subscriptionId);

    verify(webhooks).unsubscribe(subscriptionId);
    verify(audit).record(eq(userId), any(), any(), eq("webhook.unsubscribed"), any(), any());
  }

  @Test
  void anotherUsersEndpointCannotBeUnsubscribed() {
    UUID somebodyElse = UUID.randomUUID();

    assertThatThrownBy(() -> useCase.unsubscribe(somebodyElse, subscriptionId))
        .isInstanceOf(NotFoundException.class);

    verify(webhooks, never()).unsubscribe(any());
  }
}
