package com.jizza.engine.orchestrator.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.DevicePlatform;
import com.jizza.engine.api.device.DeviceStatus;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.persistence.entity.WebRecordingEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.WebRecordingRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class ChatRunPlannerTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID conversationId = UUID.randomUUID();
  private final UUID chrome = UUID.randomUUID();
  private final UUID ulaa = UUID.randomUUID();
  private WorkflowLibrary library;
  private WorkflowRepository workflows;
  private WebRecordingRepository recordings;
  private Devices devices;
  private LlmClient llm;
  private ChatRunPlanner planner;
  private WorkflowEntity booking;

  static final String GRAPH = """
      {"nodes":[
        {"id":"start","type":"TRIGGER","config":{"inputs":[
          {"name":"from","type":"text","required":true,"label":"From station"},
          {"name":"to","type":"text","required":true,"label":"To station"},
          {"name":"date","type":"date","required":true,"label":"Journey date"},
          {"name":"travelClass","type":"text","required":true,"label":"Class",
           "options":["Sleeper (SL)","AC 3 Tier (3A)"],"synonyms":{"third ac":"AC 3 Tier (3A)"}},
          {"name":"passengers","type":"list","required":true,"maxRows":4,
           "columns":[{"key":"name","required":true},{"key":"age","type":"number"}]},
          {"name":"train","type":"text","required":true,"ask":"atStep","label":"Train"}]}},
        {"id":"n1","type":"DEVICE","label":"Open the railway site",
         "config":{"preset":"web","action":"browser.open","inputs":{"url":"https://rail.example/"}}}]}""";

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    library = mock(WorkflowLibrary.class);
    workflows = mock(WorkflowRepository.class);
    recordings = mock(WebRecordingRepository.class);
    devices = mock(Devices.class);
    llm = mock(LlmClient.class);
    when(llm.modelFor(ModelTier.ROUTING)).thenReturn("cheap");

    booking = new WorkflowEntity();
    booking.setId(UUID.randomUUID());
    booking.setUserId(userId);
    booking.setName("Simon");
    booking.setStatus(WorkflowStatus.DRAFT);
    booking.setGraph(Json.read(GRAPH));
    when(workflows.findById(booking.getId())).thenReturn(Optional.of(booking));
    ReuseProfile profile = new ReuseProfile("Books a train ticket", List.of("book a train ticket"),
        List.of(), ReuseProfile.Guardrails.DEFAULT, null);
    when(library.find(eq(userId), anyString(), eq(WorkflowLibrary.Use.CHAT), anyInt())).thenReturn(List.of(
        new WorkflowLibrary.Match(booking.getId(), "Simon", null, WorkflowStatus.DRAFT, 1, profile, 0.7, List.of())));

    WebRecordingEntity recorded = new WebRecordingEntity();
    recorded.setDeviceId(ulaa);
    when(recordings.findByWorkflowIdOrderByUpdatedAtDesc(booking.getId())).thenReturn(List.of(recorded));
    when(devices.owned(userId)).thenReturn(List.of(browser(ulaa, "Ulla browser", false), browser(chrome, "Chromium browser", true)));

    planner = new ChatRunPlanner(true, 0.3, provider(library), workflows, provider(recordings),
        provider(devices), provider(llm), provider(mock(CostAccountant.class)));
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(value);
    return p;
  }

  private static DeviceDto browser(UUID id, String name, boolean online) {
    return new DeviceDto(id, null, null, name, DevicePlatform.from("browser"), null, null, 1,
        DeviceStatus.ACTIVE, List.of(), "browser", online, online ? "idle" : null, null,
        List.of("browser.open@1"), false, List.of(), Instant.now(), Instant.now());
  }

  private void answers(String json) {
    when(llm.complete(eq("cheap"), anyList(), anyList()))
        .thenReturn(new LlmResponse(json, List.of(), "end_turn", 900, 80, "cheap"));
  }

  @Test
  void aRequestInPlainWordsGetsAStartCardWithWhatItSaid() {
    answers("""
        {"run": true, "workflow": 1, "values": {"from": "MAS", "to": "NDLS", "date": "2026-12-20",
          "travelClass": "third AC", "passengers": [{"name": "Chiam"}, {"name": "Amma"}]}}""");

    Orchestrator.Turn turn = planner.handle(userId, conversationId,
        "Book 3rd AC Chennai to Delhi 20 Dec for me and Amma", Json.obj().put("timeZone", "Asia/Kolkata")).orElseThrow();

    assertThat(turn.actions()).hasSize(1);
    JsonNode card = turn.actions().get(0).payload();
    assertThat(turn.actions().get(0).type()).isEqualTo(ChatRunPlanner.START_RUN);
    assertThat(card.path("workflowId").asText()).isEqualTo(booking.getId().toString());
    JsonNode fields = card.path("fields");
    assertThat(fields.get(3).path("value").asText()).isEqualTo("AC 3 Tier (3A)");
    assertThat(fields.get(4).path("value")).hasSize(2);
    assertThat(fields.get(5).has("value")).isFalse();
    assertThat(turn.text()).contains("Ready to run **Simon**").contains("ask you for Train while it runs");
    // Recorded in Ulaa, which is offline, so the card starts on the connected Chrome.
    assertThat(card.path("browserId").asText()).isEqualTo(chrome.toString());
    assertThat(card.path("browsers")).hasSize(2);
    assertThat(turn.inputTokens()).isZero();

    // The reader is told today in the person's own time zone, and the inputs' choices.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Msg>> sent = ArgumentCaptor.forClass(List.class);
    verify(llm).complete(eq("cheap"), sent.capture(), anyList());
    String told = sent.getValue().get(1).text();
    assertThat(told).contains("Asia/Kolkata").contains("AC 3 Tier (3A)").contains("third ac = AC 3 Tier (3A)");
    assertThat(told.length()).isLessThan(4000);
  }

  @Test
  void whatIsStillNeededAndWhatDoesNotFitAreSaid() {
    answers("""
        {"run": true, "workflow": 1, "values": {"from": "MAS", "travelClass": "first class",
          "passengers": [{"name":"a"},{"name":"b"},{"name":"c"},{"name":"d"},{"name":"e"}]}}""");

    Orchestrator.Turn turn = planner.handle(userId, conversationId, "book a train from MAS", null).orElseThrow();

    JsonNode fields = turn.actions().get(0).payload().path("fields");
    assertThat(fields.get(3).path("problem").asText()).contains("not one of the choices");
    assertThat(fields.get(4).path("problem").asText()).contains("at most 4");
    assertThat(turn.text()).contains("needs 4 more details").contains("To station").contains("Journey date");
  }

  @Test
  void aMessageThatIsNotARunRequestGoesToJizza() {
    answers("{\"run\": false}");
    assertThat(planner.handle(userId, conversationId, "change the train booking workflow to use Ulaa", null)).isEmpty();
  }

  @Test
  void aQuestionOrNoMatchingWorkflowCostsNoModelCall() {
    assertThat(planner.handle(userId, conversationId, "how does the train booking work?", null)).isEmpty();
    when(library.find(eq(userId), anyString(), any(), anyInt())).thenReturn(List.of());
    assertThat(planner.handle(userId, conversationId, "write me a poem", null)).isEmpty();
    verify(llm, never()).complete(anyString(), anyList(), anyList());
  }

  @Test
  void anAnswerItCannotReadGoesToJizza() {
    answers("Sure! I'll book it.");
    assertThat(planner.handle(userId, conversationId, "book a train", null)).isEmpty();
    answers("{\"run\": true, \"workflow\": 7}");
    assertThat(planner.handle(userId, conversationId, "book a train", null)).isEmpty();
  }

  @Test
  void someoneElsesWorkflowIsNeverOffered() {
    booking.setUserId(UUID.randomUUID());
    answers("{\"run\": true, \"workflow\": 1, \"values\": {}}");
    assertThat(planner.handle(userId, conversationId, "book a train", null)).isEmpty();
  }

  @Test
  void theTimeZoneFallsBackToUtc() {
    assertThat(ChatRunPlanner.zone(null)).isEqualTo(java.time.ZoneOffset.UTC);
    assertThat(ChatRunPlanner.zone(Json.obj().put("timeZone", "Not/AZone"))).isEqualTo(java.time.ZoneOffset.UTC);
    assertThat(ChatRunPlanner.zone(Json.obj().put("timeZone", "Asia/Kolkata"))).isEqualTo(ZoneId.of("Asia/Kolkata"));
  }

  @Test
  void theRecordedBrowserIsPreferredWhenItIsConnected() {
    DeviceDto a = browser(chrome, "Chrome", true);
    DeviceDto b = browser(ulaa, "Ulaa", true);
    assertThat(ChatRunPlanner.defaultBrowser(List.of(a, b), ulaa)).isEqualTo(ulaa);
    assertThat(ChatRunPlanner.defaultBrowser(List.of(browser(chrome, "Chrome", false)), ulaa)).isEqualTo(chrome);
    assertThat(ChatRunPlanner.defaultBrowser(List.of(), ulaa)).isNull();
  }

  @Test
  void namesTheFirstStepThatCannotBeUndone() {
    var graph = com.jizza.engine.api.util.Json.read("""
        {"nodes":[{"id":"a","label":"Search","config":{}},
          {"id":"b","label":"Old pay","config":{"irreversible":true,"disabled":true}},
          {"id":"c","label":"Pay now","config":{"irreversible":true}}]}""");
    assertThat(ChatRunPlanner.finalStep(graph)).isEqualTo("Pay now");
    assertThat(ChatRunPlanner.finalStep(com.jizza.engine.api.util.Json.read("{\"nodes\":[]}"))).isNull();
  }
}
