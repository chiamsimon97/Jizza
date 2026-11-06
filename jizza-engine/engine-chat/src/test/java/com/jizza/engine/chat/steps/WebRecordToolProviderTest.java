package com.jizza.engine.chat.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.Recordings;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.DeviceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WebRecordToolProviderTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID jizzaId = UUID.randomUUID();
  private final AgentRepository agents = mock(AgentRepository.class);
  private final DeviceRepository devices = mock(DeviceRepository.class);
  private final Recordings recordings = mock(Recordings.class);
  @SuppressWarnings("unchecked")
  private final ObjectProvider<Recordings> provider = mock(ObjectProvider.class);
  private final WebRecordToolProvider tool;

  WebRecordToolProviderTest() {
    AgentEntity jizza = new AgentEntity();
    jizza.setId(jizzaId);
    jizza.setUserId(userId);
    jizza.setOrchestrator(true);
    when(agents.findById(jizzaId)).thenReturn(Optional.of(jizza));
    when(provider.getIfAvailable()).thenReturn(recordings);
    tool = new WebRecordToolProvider(agents, devices, provider);
  }

  private ChatScope.Scope scope() {
    return ChatScope.Scope.thread(userId, UUID.randomUUID(), UUID.randomUUID(), jizzaId, "Plan", "Money");
  }

  private DeviceEntity browser(String name, String... actions) {
    DeviceEntity d = new DeviceEntity();
    d.setId(UUID.randomUUID());
    d.setWorkspaceId(UUID.randomUUID());
    d.setName(name);
    d.setPlatform("browser");
    var list = Json.mapper().createArrayNode();
    for (String a : actions) list.add(a);
    d.setActions(list);
    return d;
  }

  private Recordings.Recording recording(UUID id, String status, int steps) {
    return new Recordings.Recording(id, "Invoices", status, UUID.randomUUID(), "MacBook",
        "https://example.com", steps, null, Instant.now(), null);
  }

  private ToolResult call(JsonNode args) {
    return ChatScope.run(scope(), () -> tool.invoke(jizzaId, new ToolCall("1", "web_record", args)));
  }

  @Test
  void startingARecordingPutsAStepsCardWithStopAndReviewOnTheReply() {
    DeviceEntity mac = browser("Chrome on MacBook", "browser.record@1", "browser.open@2");
    when(devices.findByOwnerUserIdAndStatusOrderByCreatedAtAsc(userId, "active")).thenReturn(List.of(mac));
    UUID id = UUID.randomUUID();
    when(recordings.start(any(), eq(mac.getId()), eq("https://example.com"), eq("Invoices")))
        .thenReturn(recording(id, "recording", 0));

    ChatScope.Scope scope = scope();
    ToolResult result = ChatScope.run(scope, () -> tool.invoke(jizzaId, new ToolCall("1", "web_record",
        Json.obj().put("url", "https://example.com").put("name", "Invoices"))));

    assertThat(result.ok()).isTrue();
    List<UiAction> actions = ChatScope.actions(scope);
    assertThat(actions).singleElement().satisfies(a -> {
      assertThat(a.type()).isEqualTo(UiAction.SHOW_STEPS);
      assertThat(a.payload().path("title").asText()).contains("Chrome on MacBook");
      assertThat(a.payload().at("/steps/1/button/label").asText()).isEqualTo("Stop");
      assertThat(a.payload().at("/steps/1/button/text").asText()).contains(id.toString());
      assertThat(a.payload().at("/steps/2/button/label").asText()).isEqualTo("Open review");
    });
  }

  @Test
  void withTwoBrowsersItAsksWhichAndStartsNothing() {
    when(devices.findByOwnerUserIdAndStatusOrderByCreatedAtAsc(userId, "active")).thenReturn(
        List.of(browser("A", "browser.record@1"), browser("B", "browser.record@1")));
    ToolResult result = call(Json.obj().put("url", "https://example.com").put("name", "X"));
    assertThat(result.ok()).isFalse();
    verify(recordings, never()).start(any(), any(), any(), any());
  }

  @Test
  void aBrowserThatCannotRecordIsNotOffered() {
    when(devices.findByOwnerUserIdAndStatusOrderByCreatedAtAsc(userId, "active"))
        .thenReturn(List.of(browser("A", "browser.open@2")));
    assertThat(call(Json.obj().put("url", "https://example.com").put("name", "X")).ok()).isFalse();
  }

  @Test
  void stopAndReviewWorkFromTheCardsReplies() {
    UUID id = UUID.randomUUID();
    when(recordings.stop(any(), eq(id))).thenReturn(recording(id, "review", 4));
    when(recordings.get(any(), eq(id))).thenReturn(recording(id, "review", 4));

    assertThat(call(Json.obj().put("action", "stop").put("recordingId", id.toString())).ok()).isTrue();

    ChatScope.Scope scope = scope();
    ChatScope.run(scope, () -> tool.invoke(jizzaId, new ToolCall("1", "web_record",
        Json.obj().put("action", "review").put("recordingId", id.toString()))));
    assertThat(ChatScope.actions(scope)).singleElement().satisfies(a -> {
      assertThat(a.type()).isEqualTo(UiAction.NAVIGATE);
      assertThat(a.payload().path("page").asText()).isEqualTo("recordings");
      assertThat(a.payload().path("review").asText()).isEqualTo(id.toString());
    });
  }

  @Test
  void onlyJizzaAnsweringTheUserGetsIt() {
    assertThat(tool.toolsFor(jizzaId)).isEmpty();
    ChatScope.run(scope(), () -> {
      assertThat(tool.toolsFor(jizzaId)).hasSize(1);
      assertThat(tool.toolsFor(UUID.randomUUID())).isEmpty();
      return null;
    });
  }
}
