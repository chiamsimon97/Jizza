package com.jizza.platform.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.device.DeviceAgentGateway.Companion;
import com.jizza.engine.api.device.PhoneAccess;
import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.enums.EscalationState;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.DesignTimeService;
import com.jizza.platform.usecase.notification.NotificationUseCase;
import com.jizza.platform.usecase.webauto.WebAutomationUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Refresh on a phone's approval card reads only the fields the card offers, on its own browser. */
class PhoneChoicesTest {

  private final UUID user = UUID.randomUUID();
  private final UUID workspace = UUID.randomUUID();
  private final UUID browser = UUID.randomUUID();
  private final UUID item = UUID.randomUUID();
  private final HttpServletRequest http = mock(HttpServletRequest.class);
  private PhoneRequests phones;
  private NotificationUseCase notifications;
  private WebAutomationUseCase web;
  private PhoneController controller;

  @BeforeEach
  void setUp() {
    phones = mock(PhoneRequests.class);
    notifications = mock(NotificationUseCase.class);
    web = mock(WebAutomationUseCase.class);
    when(phones.signed(any(), any(), eq(PhoneAccess.APPROVE)))
        .thenReturn(new Companion(user, workspace, UUID.randomUUID(), "Pixel", PhoneAccess.APPROVE));
    controller = new PhoneController(phones, notifications, null, null, null, null, null, null,
        null, null, null, mock(AuditLog.class), null, null, null, null, web);
  }

  private void card(NotificationStatus status) {
    JsonNode context = Json.read("""
        {"form": {"fields": [{"key": "coach", "type": "text"}],
          "live": {"device": "%s", "session": "s-1", "fields": {
            "coach": {"action": "browser.readOptions", "version": 2, "element": {"css": "#coach"}},
            "city": {"action": "browser.suggest", "version": 1, "typed": true, "element": {"css": "#city"}}}}}}
        """.formatted(browser));
    when(notifications.get(user, item)).thenReturn(new NotificationDto(item, user, null, null,
        NotificationKind.PERMISSION_REQUEST, "Choose", context, null, null, UUID.randomUUID(),
        status, null, null, EscalationState.NONE, Instant.now(), null));
  }

  private byte[] body(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void readsADropdownOnTheBrowserTheCardNames() {
    card(NotificationStatus.UNREAD);
    when(web.testStep(any(), any(), anyString(), anyInt(), any())).thenReturn(
        new DesignTimeService.Result("succeeded",
            Json.read("{\"items\": [{\"label\": \" SL \"}, {\"label\": \"3A\"}, {\"label\": \"SL\"}, {}]}"),
            null, List.of(), null));

    PhoneController.Choices read = controller.choices(item, body("{\"key\": \"coach\"}"), http);

    assertThat(read.options()).containsExactly("SL", "3A");
    assertThat(read.error()).isNull();
    ArgumentCaptor<JsonNode> inputs = ArgumentCaptor.forClass(JsonNode.class);
    verify(web).testStep(eq(new Access(user, workspace)), eq(browser), eq("browser.readOptions"),
        eq(2), inputs.capture());
    assertThat(inputs.getValue().path("session").asText()).isEqualTo("s-1");
    assertThat(inputs.getValue().path("element").path("css").asText()).isEqualTo("#coach");
    assertThat(inputs.getValue().has("text")).isFalse();
  }

  @Test
  void aTypedFieldNeedsTextAndSuggestsForIt() {
    card(NotificationStatus.READ);
    PhoneController.Choices empty = controller.choices(item, body("{\"key\": \"city\"}"), http);
    assertThat(empty.error()).isEqualTo("Type a few letters first, then refresh.");
    verify(web, never()).testStep(any(), any(), anyString(), anyInt(), any());

    when(web.testStep(any(), any(), anyString(), anyInt(), any())).thenReturn(
        new DesignTimeService.Result("succeeded", Json.read("{\"options\": []}"), null, List.of(), null));
    PhoneController.Choices none =
        controller.choices(item, body("{\"key\": \"city\", \"typed\": \"Chen\"}"), http);
    assertThat(none.options()).isEmpty();
    assertThat(none.error()).isEqualTo("The page suggests nothing for \"Chen\".");
  }

  @Test
  void aFailedReadSaysWhy() {
    card(NotificationStatus.UNREAD);
    when(web.testStep(any(), any(), anyString(), anyInt(), any())).thenReturn(
        new DesignTimeService.Result("failed", null,
            new DesignTimeService.Error("not_found", "The dropdown is gone", null), List.of(), null));
    assertThat(controller.choices(item, body("{\"key\": \"coach\"}"), http).error())
        .isEqualTo("The dropdown is gone");
  }

  @Test
  void onlyTheCardsOwnFieldsWhileItIsOpen() {
    card(NotificationStatus.UNREAD);
    assertThatThrownBy(() -> controller.choices(item, body("{\"key\": \"price\"}"), http))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> controller.choices(item, body("{}"), http))
        .isInstanceOf(ValidationException.class);
    card(NotificationStatus.APPROVED);
    assertThatThrownBy(() -> controller.choices(item, body("{\"key\": \"coach\"}"), http))
        .isInstanceOf(ValidationException.class);
    verify(web, never()).testStep(any(), any(), anyString(), anyInt(), any());
  }
}
