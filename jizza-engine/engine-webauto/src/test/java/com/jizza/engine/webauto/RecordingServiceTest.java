package com.jizza.engine.webauto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.device.DeviceActionCatalog;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceCommandDto;
import com.jizza.engine.api.device.DeviceCommandStatus;
import com.jizza.engine.api.device.DeviceCommands;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.DevicePlatform;
import com.jizza.engine.api.device.DeviceStatus;
import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.realtime.RealtimeGateway;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.Recordings;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.persistence.entity.WebRecordingEntity;
import com.jizza.engine.persistence.entity.WebRecordingStepEntity;
import com.jizza.engine.persistence.repository.WebRecordingRepository;
import com.jizza.engine.persistence.repository.WebRecordingStepRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RecordingServiceTest {

  private final Map<UUID, WebRecordingEntity> recordingRows = new HashMap<>();
  private final List<WebRecordingStepEntity> stepRows = new ArrayList<>();
  private final List<Object> published = new ArrayList<>();
  private final List<WorkflowDto> savedWorkflows = new ArrayList<>();

  private DeviceCommands commands;
  private WorkflowRegistry workflows;
  private Devices devices;
  private Access access;
  private DeviceDto browser;
  private RecordingService service;
  private final UUID commandId = UUID.randomUUID();

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    WebRecordingRepository recordings = mock(WebRecordingRepository.class);
    when(recordings.saveAndFlush(any())).thenAnswer(i -> store(i.getArgument(0)));
    when(recordings.save(any())).thenAnswer(i -> store(i.getArgument(0)));
    when(recordings.findById(any())).thenAnswer(i -> Optional.ofNullable(recordingRows.get(i.<UUID>getArgument(0))));
    when(recordings.lockById(any())).thenAnswer(i -> Optional.ofNullable(recordingRows.get(i.<UUID>getArgument(0))));
    when(recordings.findByOwnerIdAndStatusInOrderByUpdatedAtDesc(any(), any())).thenAnswer(i ->
        recordingRows.values().stream()
            .filter(r -> r.getOwnerId().equals(i.getArgument(0)))
            .filter(r -> ((java.util.Collection<String>) i.getArgument(1)).contains(r.getStatus()))
            .toList());

    WebRecordingStepRepository steps = mock(WebRecordingStepRepository.class);
    when(steps.ordered(any())).thenAnswer(i -> stepRows.stream()
        .filter(s -> s.getRecordingId().equals(i.getArgument(0)))
        .sorted(Comparator.comparingInt(WebRecordingStepEntity::getPosition)
            .thenComparingInt(WebRecordingStepEntity::getSeq))
        .toList());
    when(steps.save(any())).thenAnswer(i -> {
      WebRecordingStepEntity s = i.getArgument(0);
      if (!stepRows.contains(s)) stepRows.add(s);
      return s;
    });

    access = new Access(UUID.randomUUID(), UUID.randomUUID());
    browser = new DeviceDto(UUID.randomUUID(), access.workspaceId(), access.userId(), "Chrome",
        DevicePlatform.BROWSER, null, null, 1, DeviceStatus.ACTIVE, List.of(), "browser", true,
        "idle", Json.obj(), List.of("browser.record@1"), false, List.of(), Instant.now(),
        Instant.now());
    devices = mock(Devices.class);
    when(devices.list(access.workspaceId(), access.userId())).thenReturn(List.of(browser));
    when(devices.find(browser.id())).thenReturn(Optional.of(browser));

    commands = mock(DeviceCommands.class);
    when(commands.submit(any())).thenAnswer(i -> command(DeviceCommandStatus.DISPATCHED));
    when(commands.find(commandId)).thenAnswer(i -> Optional.of(command(DeviceCommandStatus.DISPATCHED)));

    DeviceActionCatalog catalog = mock(DeviceActionCatalog.class);
    when(catalog.resolve("ui.record", 0)).thenReturn(Optional.of(new DeviceActionSpec(
        "ui.record", 1, "ui", ActionType.READ, "Record app actions", null, Json.obj(), Json.obj(),
        List.of("app"), List.of("windows"), 1800)));
    when(catalog.resolve("browser.record", 0)).thenReturn(Optional.of(new DeviceActionSpec(
        "browser.record", 1, "browser", ActionType.READ, "Record", null, Json.obj(), Json.obj(),
        List.of("site"), List.of("browser"), 1800)));
    AgentDirectory agents = mock(AgentDirectory.class);
    when(agents.orchestrator(any())).thenReturn(new AgentDto(UUID.randomUUID(), access.userId(),
        "jizza", "Jizza", null, "", true, true));
    AutonomyGate autonomy = mock(AutonomyGate.class);
    when(autonomy.decide(any())).thenReturn(Decision.allow("default"));

    EncryptionService encryption = new EncryptionService() {
      public byte[] encrypt(byte[] plain) { return java.util.Base64.getEncoder().encode(plain); }
      public byte[] decrypt(byte[] ct) { return java.util.Base64.getDecoder().decode(ct); }
    };
    workflows = mock(WorkflowRegistry.class);
    when(workflows.save(any())).thenAnswer(i -> {
      WorkflowDto w = i.getArgument(0);
      savedWorkflows.add(w);
      return new WorkflowDto(UUID.randomUUID(), w.userId(), w.name(), w.description(), w.graph(),
          w.inputSchema(), w.triggerConfig(), w.origin(), w.status(), null, 1, null, Instant.now());
    });
    ObjectProvider<WorkflowLibrary> library = mock(ObjectProvider.class);
    RealtimeGateway gateway = mock(RealtimeGateway.class);
    org.mockito.Mockito.doAnswer(i -> published.add(i.getArgument(2)))
        .when(gateway).publish(any(), anyString(), any());
    ObjectProvider<RealtimeGateway> realtime = mock(ObjectProvider.class);
    when(realtime.getIfAvailable()).thenReturn(gateway);

    service = new RecordingService(recordings, steps, devices, commands, catalog, encryption,
        workflows, library, realtime, agents, autonomy);
  }

  private WebRecordingEntity store(WebRecordingEntity r) {
    recordingRows.put(r.getId(), r);
    return r;
  }

  private DeviceCommandDto command(DeviceCommandStatus status) {
    return new DeviceCommandDto(commandId, browser.id(), browser.name(), access.userId(), null,
        null, "browser.record", 1, Json.obj(), status, null, null, null, null, List.of(),
        Instant.now().plusSeconds(1800), Instant.now(), null, null,
        status.finished() ? Instant.now().minusSeconds(10) : null);
  }

  private static JsonNode step(int seq, String action, String label, String input, boolean sensitive) {
    return Json.read("{\"seq\":" + seq + ",\"action\":\"" + action + "\",\"version\":1,\"label\":\""
        + label + "\",\"input\":" + input + ",\"meta\":{\"url\":\"https://portal.example.com/a\","
        + "\"sensitive\":" + sensitive + "}}");
  }

  private JsonNode event(UUID recordingId, JsonNode step) {
    var data = Json.obj().put("recordingId", recordingId.toString());
    data.set("step", step);
    return data;
  }

  private Recordings.Recording started() {
    return service.start(access, browser.id(), "https://portal.example.com", "Invoices");
  }

  @Test
  void startsARecordingOnTheBrowserAndNamesTheCommand() {
    var recording = started();

    assertThat(recording.status()).isEqualTo("recording");
    assertThat(recordingRows.get(recording.id()).getCommandId()).isEqualTo(commandId);
    verify(commands).submit(org.mockito.ArgumentMatchers.argThat(r ->
        r.interactive() && "browser.record".equals(r.action())
            && r.input().path("recordingId").asText().equals(recording.id().toString())
            && r.input().path("maxMinutes").asInt() == 30));
  }

  @Test
  void refusesASecondRecordingOnTheSameBrowserAndABadUrl() {
    started();
    assertThatThrownBy(this::started).isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> service.start(access, browser.id(), "javascript:alert(1)", "x"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void storesStepsInSeqOrderIgnoringDuplicatesAndOutOfOrderArrivals() {
    var id = started().id();
    service.accept(browser.id(), access.userId(), "browser.recording.step",
        event(id, step(2, "browser.click", "Click B", "{}", false)));
    service.accept(browser.id(), access.userId(), "browser.recording.step",
        event(id, step(0, "browser.open", "Open", "{\"url\":\"https://portal.example.com\"}", false)));
    service.accept(browser.id(), access.userId(), "browser.recording.step",
        event(id, step(2, "browser.click", "Click B again", "{}", false)));
    service.accept(browser.id(), access.userId(), "browser.recording.step",
        event(id, step(1, "browser.click", "Click A", "{}", false)));

    var steps = service.get(access, id).steps();
    assertThat(steps).extracting(Recordings.RecordedStep::seq).containsExactly(0, 1, 2);
    assertThat(steps.get(2).step().path("label").asText()).isEqualTo("Click B");
    assertThat(recordingRows.get(id).getStepCount()).isEqualTo(3);
    assertThat(published).isNotEmpty();
  }

  @Test
  void dropsEventsFromAnotherDeviceOwnerOrAFinishedRecording() {
    var id = started().id();
    var ev = event(id, step(0, "browser.click", "Click", "{}", false));

    service.accept(UUID.randomUUID(), access.userId(), "browser.recording.step", ev);
    service.accept(browser.id(), UUID.randomUUID(), "browser.recording.step", ev);
    assertThat(stepRows).isEmpty();

    service.accept(browser.id(), access.userId(), "browser.recording.stopped",
        Json.obj().put("recordingId", id.toString()));
    assertThat(recordingRows.get(id).getStatus()).isEqualTo("review");
    recordingRows.get(id).setStoppedAt(java.time.Instant.now().minusSeconds(10));
    service.accept(browser.id(), access.userId(), "browser.recording.step", ev);
    assertThat(stepRows).isEmpty();
  }

  @Test
  void aStepHandledJustAfterStoppedIsStillKept() {
    var id = started().id();
    service.accept(browser.id(), access.userId(), "browser.recording.stopped",
        Json.obj().put("recordingId", id.toString()));
    service.accept(browser.id(), access.userId(), "browser.recording.step",
        event(id, step(0, "browser.click", "Last click", "{}", false)));

    assertThat(stepRows).hasSize(1);
    assertThat(recordingRows.get(id).getStepCount()).isEqualTo(1);
    assertThat(recordingRows.get(id).getStatus()).isEqualTo("review");
  }

  @Test
  void aSensitiveStepNeverKeepsItsValue() {
    var id = started().id();
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(0, "browser.setText", "Password", "{\"text\":\"hunter2\"}", true)));

    assertThat(new String(stepRows.get(0).getStep()))
        .isNotEqualTo("").doesNotContain("hunter2");
    assertThat(new String(java.util.Base64.getDecoder().decode(stepRows.get(0).getStep())))
        .doesNotContain("hunter2");
  }

  @Test
  void reviewEditsDeleteMoveMapAndPickALoginThenSaveARecordedWorkflow() {
    var id = started().id();
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(0, "browser.open", "Open", "{\"url\":\"https://portal.example.com\"}", false)));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(1, "browser.setText", "Invoice", "{\"element\":{\"strategies\":[{\"by\":\"css\",\"value\":\"#i\"}]},\"text\":\"INV-1\"}", false)));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(2, "browser.setText", "Password", "{\"text\":\"x\"}", true)));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(3, "browser.click", "Oops", "{}", false)));
    service.stop(access, id);
    verify(commands).cancel(any(), anyString());

    var edited = service.edit(access, id, List.of(
        new Recordings.EditOp("delete", 3, null, null, null, null, null, null),
        new Recordings.EditOp("move", 2, 1, null, null, null, null, null),
        new Recordings.EditOp("relabel", 1, null, "Invoice number", null, null, null, null),
        new Recordings.EditOp("map", 1, null, null, "text",
            new Recordings.Input("invoiceNo", "text", null), null, null)));

    assertThat(edited.steps().stream().filter(s -> !s.deleted()).map(s -> s.seq()).toList())
        .containsExactly(0, 2, 1);
    assertThat(edited.stepCount()).isEqualTo(3);

    assertThatThrownBy(() -> service.save(access, id,
        new Recordings.SaveRequest("Invoices", null, null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Choose a saved login for the password step");

    service.edit(access, id, List.of(
        new Recordings.EditOp("secret", 2, null, null, null, null, "login-1", null)));
    var saved = service.save(access, id, new Recordings.SaveRequest("Invoices", "Pays invoices", null));

    assertThat(saved.workflowId()).isNotNull();
    var workflow = savedWorkflows.get(0);
    assertThat(workflow.origin()).isEqualTo(WorkflowOrigin.RECORDED);
    assertThat(workflow.graph().toString()).contains("{{ input.invoiceNo }}")
        .doesNotContain("hunter2").doesNotContain("\"text\":\"x\"");
    assertThat(recordingRows.get(id).getStatus()).isEqualTo("saved");
    assertThat(recordingRows.get(id).getWorkflowId()).isEqualTo(saved.workflowId());
  }

  @Test
  void theSidePanelRenamesAndMakesInputsButNeverFromAPassword() {
    var id = started().id();
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(1, "browser.setText", "Invoice", "{\"text\":\"INV-1\"}", false)));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(2, "browser.setText", "Password", "{\"text\":\"x\"}", true)));
    var relabel = Json.obj().put("recordingId", id.toString()).put("op", "relabel").put("seq", 1)
        .put("label", "Invoice number");
    var map = Json.obj().put("recordingId", id.toString()).put("op", "map").put("seq", 1)
        .put("field", "text");
    map.putObject("input").put("name", "invoiceNo").put("type", "text");
    var mapPassword = Json.obj().put("recordingId", id.toString()).put("op", "map").put("seq", 2)
        .put("field", "text");
    mapPassword.putObject("input").put("name", "pw").put("type", "text");
    var secret = Json.obj().put("recordingId", id.toString()).put("op", "secret").put("seq", 2)
        .put("secretRef", "login-1");
    for (var edit : List.of(relabel, map, mapPassword, secret)) {
      service.accept(browser.id(), access.userId(), "browser.recording.edit", edit);
    }

    var steps = service.get(access, id).steps();
    var invoice = steps.stream().filter(s -> s.seq() == 1).findFirst().orElseThrow().step();
    assertThat(invoice.path("label").asText()).isEqualTo("Invoice number");
    assertThat(invoice.path("mappings").path("text").path("name").asText()).isEqualTo("invoiceNo");
    assertThat(invoice.path("mappings").path("text").path("default").asText()).isEqualTo("INV-1");
    var password = steps.stream().filter(s -> s.seq() == 2).findFirst().orElseThrow().step();
    assertThat(password.has("mappings")).isFalse();
    assertThat(password.path("secretRef").isNull()).isTrue();
  }

  @Test
  void aManualFieldsDropdownChoiceDateAndCheckboxBecomeInputs() {
    var id = started().id();
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(1, "browser.selectItem", "Class",
            "{\"element\":{\"strategies\":[{\"by\":\"css\",\"value\":\"#c\"}]},"
                + "\"by\":\"label\",\"items\":[\"Sleeper\"]}", false)));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(2, "browser.pickDate", "Journey",
            "{\"element\":{\"strategies\":[{\"by\":\"css\",\"value\":\"#d\"}]},"
                + "\"date\":\"20 Nov 2026\"}", false)));
    for (String[] edit : new String[][] {{"1", "items", "travelClass", "text"},
        {"2", "date", "journeyDate", "date"}}) {
      var map = Json.obj().put("recordingId", id.toString()).put("op", "map")
          .put("seq", Integer.parseInt(edit[0])).put("field", edit[1]);
      map.putObject("input").put("name", edit[2]).put("type", edit[3]);
      service.accept(browser.id(), access.userId(), "browser.recording.edit", map);
    }
    var steps = service.get(access, id).steps();
    var travelClass = steps.stream().filter(s -> s.seq() == 1).findFirst().orElseThrow().step();
    assertThat(travelClass.path("mappings").path("items").path("name").asText())
        .isEqualTo("travelClass");
    assertThat(travelClass.path("mappings").path("items").path("default").asText())
        .isEqualTo("Sleeper");
    var date = steps.stream().filter(s -> s.seq() == 2).findFirst().orElseThrow().step();
    assertThat(date.path("mappings").path("date").path("type").asText()).isEqualTo("date");

    service.stop(access, id);
    service.save(access, id, new Recordings.SaveRequest("Book", null, null));
    assertThat(savedWorkflows.get(0).graph().toString())
        .contains("\"items\":\"{{ input.travelClass }}\"")
        .contains("{{ input.journeyDate }}");
  }

  @Test
  void eachFieldOfAFormStepCanBecomeAnInput() {
    var id = started().id();
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(1, "browser.fillForm", "Passenger details",
            "{\"fields\":[{\"element\":{\"strategies\":[{\"by\":\"css\",\"value\":\"#n\"}]},"
                + "\"kind\":\"text\",\"label\":\"Name\",\"value\":\"Asha\"},"
                + "{\"element\":{\"strategies\":[{\"by\":\"css\",\"value\":\"#a\"}]},"
                + "\"kind\":\"text\",\"label\":\"Age\",\"value\":\"34\"}]}", false)));
    var map = Json.obj().put("recordingId", id.toString()).put("op", "map")
        .put("seq", 1).put("field", "fields.1.value");
    map.putObject("input").put("name", "age").put("type", "text");
    service.accept(browser.id(), access.userId(), "browser.recording.edit", map);

    var step = service.get(access, id).steps().get(0).step();
    assertThat(step.path("mappings").path("fields.1.value").path("default").asText())
        .isEqualTo("34");
    service.stop(access, id);
    service.save(access, id, new Recordings.SaveRequest("Book", null, null));
    var graph = savedWorkflows.get(0).graph().toString();
    assertThat(graph).contains("\"value\":\"{{ input.age }}\"").contains("\"value\":\"Asha\"");
  }

  @Test
  void aListChoiceInputDoesNotKeepTheItemRecorded() {
    var id = started().id();
    var step = (com.fasterxml.jackson.databind.node.ObjectNode) step(1, "browser.clickItem",
        "Choose Showtime", "{\"element\":{\"strategies\":[{\"by\":\"css\",\"value\":\".t\"}]},"
            + "\"item\":\"07:15 PM\",\"within\":{\"element\":{\"strategies\":[{\"by\":\"css\","
            + "\"value\":\".v\"}]},\"item\":\"PVR\"}}", false);
    ((com.fasterxml.jackson.databind.node.ObjectNode) step.get("meta")).set("list", Json.read(
        "{\"title\":\"Showtime\",\"withinTitle\":\"Theatre\",\"kind\":\"time\",\"count\":2}"));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id, step));
    service.stop(access, id);
    service.edit(access, id, List.of(
        new Recordings.EditOp("map", 1, null, null, "item",
            new Recordings.Input("time", "text", null), null, null),
        new Recordings.EditOp("map", 1, null, null, "within.item",
            new Recordings.Input("theatre", "text", null), null, null)));

    // Each run chooses from what the page lists then: no recorded theatre or showtime is kept.
    var saved = service.get(access, id).steps().get(0).step();
    assertThat(saved.path("mappings").path("item").path("default").asText()).isEmpty();
    assertThat(saved.path("mappings").path("within.item").path("default").asText()).isEmpty();
    service.save(access, id, new Recordings.SaveRequest("Book", null, null));
    var graph = savedWorkflows.get(0).graph().toString();
    assertThat(graph).doesNotContain("\"default\":\"PVR\"")
        .doesNotContain("\"default\":\"07:15 PM\"");
  }

  @Test
  void aNamedListKeepsItsNamesAndChoicesForTheBuilder() {
    var id = started().id();
    var step = (com.fasterxml.jackson.databind.node.ObjectNode) step(1, "browser.clickItem",
        "Choose Showtime", "{\"element\":{\"strategies\":[{\"by\":\"css\",\"value\":\".t\"}]},"
            + "\"item\":\"07:15 PM\",\"within\":{\"element\":{\"strategies\":[{\"by\":\"css\","
            + "\"value\":\".v\"}]},\"item\":\"PVR\",\"titleCss\":\"h3\"}}", false);
    ((com.fasterxml.jackson.databind.node.ObjectNode) step.get("meta")).set("list", Json.read(
        "{\"title\":\"Showtime\",\"withinTitle\":\"Theatre\",\"named\":true,\"kind\":\"time\","
            + "\"count\":2,\"options\":[\"07:15 PM\",\"10:45 PM\"],"
            + "\"groups\":[{\"label\":\"PVR\",\"items\":[\"07:15 PM\",\"10:45 PM\"]},"
            + "{\"label\":\"INOX\",\"items\":[\"01:00 PM\"]}],"
            + "\"dependsOn\":{\"title\":\"Theatre\",\"inputName\":\"theatre\","
            + "\"relation\":\"inside\"}}"));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id, step));
    service.stop(access, id);
    service.save(access, id, new Recordings.SaveRequest("Book", null, null));

    JsonNode node = null;
    for (JsonNode n : savedWorkflows.get(0).graph().path("nodes")) {
      if ("browser.clickItem".equals(n.path("config").path("action").asText())) node = n;
    }
    assertThat(node).isNotNull();
    JsonNode list = node.path("config").path("list");
    assertThat(list.path("title").asText()).isEqualTo("Showtime");
    assertThat(list.path("withinTitle").asText()).isEqualTo("Theatre");
    assertThat(list.path("named").asBoolean()).isTrue();
    assertThat(list.path("groups").get(1).path("label").asText()).isEqualTo("INOX");
    assertThat(list.path("dependsOn").path("title").asText()).isEqualTo("Theatre");
    assertThat(list.path("dependsOn").path("inputName").asText()).isEqualTo("theatre");
    assertThat(list.path("dependsOn").path("relation").asText()).isEqualTo("inside");
    assertThat(node.path("config").path("inputs").path("within").path("titleCss").asText())
        .isEqualTo("h3");
  }

  @Test
  void theItemAndCardOfAListChoiceBecomeInputs() {
    var id = started().id();
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(1, "browser.clickItem", "Choose the time",
            "{\"element\":{\"strategies\":[{\"by\":\"css\",\"value\":\".t\"}]},\"item\":\"01:15 PM\","
                + "\"within\":{\"element\":{\"strategies\":[{\"by\":\"css\",\"value\":\".v\"}]},"
                + "\"item\":\"Vijay Park\"}}", false)));
    service.stop(access, id);
    service.edit(access, id, List.of(
        new Recordings.EditOp("map", 1, null, null, "item",
            new Recordings.Input("time", "text", null), null, null),
        new Recordings.EditOp("map", 1, null, null, "within.item",
            new Recordings.Input("cinema", "text", null), null, null)));

    var step = service.get(access, id).steps().get(0).step();
    assertThat(step.path("mappings").path("within.item").path("default").asText())
        .isEqualTo("Vijay Park");
    assertThat(step.path("mappings").path("item").path("default").asText()).isEqualTo("01:15 PM");
    assertThatThrownBy(() -> service.edit(access, id, List.of(
        new Recordings.EditOp("map", 1, null, null, "within",
            new Recordings.Input("x", "text", null), null, null))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void insertsACheckBuiltFromAnEarlierElement() {
    var id = started().id();
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(0, "browser.click", "Submit", "{\"element\":{\"strategies\":[{\"by\":\"css\",\"value\":\"#s\"}]}}", false)));
    service.stop(access, id);

    var edited = service.edit(access, id, List.of(new Recordings.EditOp("insertCheck", 0, null,
        null, null, null, null, new Recordings.Check("browser.exists", 0))));

    assertThat(edited.steps()).hasSize(2);
    assertThat(edited.steps().get(1).step().path("action").asText()).isEqualTo("browser.exists");
    assertThat(edited.steps().get(1).step().path("input").path("element").path("strategies")).hasSize(1);
  }

  @Test
  void aStoppedCommandMovesARecordingToReviewAndOthersCannotSeeIt() {
    var id = started().id();
    when(commands.find(commandId)).thenReturn(Optional.of(command(DeviceCommandStatus.SUCCEEDED)));

    assertThat(service.get(access, id).status()).isEqualTo("review");

    Access other = new Access(access.workspaceId(), UUID.randomUUID());
    assertThatThrownBy(() -> service.get(other, id)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.discard(other, id)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void editingARecordingStillBeingRecordedIsRefused() {
    var id = started().id();
    assertThatThrownBy(() -> service.edit(access, id, List.of()))
        .isInstanceOf(ConflictException.class);
  }

  // ---- desktop recordings (hotkey on a Windows PC) -------------------------------------------

  private DeviceDto windowsPc(Devices devices) {
    DeviceDto pc = new DeviceDto(UUID.randomUUID(), access.workspaceId(), access.userId(), "Office PC",
        DevicePlatform.WINDOWS, null, null, 1, DeviceStatus.ACTIVE, List.of(), "tpm", true,
        "idle", Json.obj(), List.of("ui.record@1", "ui.click@1"), false, List.of(), Instant.now(),
        Instant.now());
    when(devices.find(pc.id())).thenReturn(Optional.of(pc));
    return pc;
  }

  private JsonNode startedEvent(UUID id, String app) {
    return Json.obj().put("recordingId", id.toString()).put("app", app);
  }

  @Test
  void aHotkeyRecordingIsKeptAndFollowedByAnAttachingCommand() throws Exception {
    var pc = windowsPc(devicesMock());
    var id = UUID.randomUUID();

    service.accept(pc.id(), access.userId(), "desktop.recording.started", startedEvent(id, "Notepad"));
    service.accept(pc.id(), access.userId(), "desktop.recording.started", startedEvent(id, "Notepad"));
    service.accept(pc.id(), access.userId(), "desktop.recording.step", event(id, Json.read(
        "{\"seq\":0,\"action\":\"ui.openApp\",\"version\":1,\"label\":\"Open Notepad\","
            + "\"input\":{\"app\":\"notepad\"},\"meta\":{\"window\":\"Untitled - Notepad\"}}")));
    // A browser step, or a recording event of the other kind, is not part of it.
    service.accept(pc.id(), access.userId(), "desktop.recording.step",
        event(id, step(1, "browser.click", "Click", "{}", false)));
    service.accept(pc.id(), access.userId(), "browser.recording.step",
        event(id, step(2, "browser.click", "Click", "{}", false)));

    var recording = service.get(access, id);
    assertThat(recording.kind()).isEqualTo("desktop");
    assertThat(recording.app()).isEqualTo("Notepad");
    assertThat(recording.name()).isEqualTo("Notepad task");
    assertThat(recording.startUrl()).isNull();
    assertThat(recording.steps()).extracting(Recordings.RecordedStep::seq).containsExactly(0);
    verify(commands, org.mockito.Mockito.times(1)).submit(org.mockito.ArgumentMatchers.argThat(r ->
        "ui.record".equals(r.action()) && r.input().path("attach").asBoolean()
            && r.input().path("recordingId").asText().equals(id.toString())));

    service.accept(pc.id(), access.userId(), "desktop.recording.stopped",
        Json.obj().put("recordingId", id.toString()));
    service.save(access, id, new Recordings.SaveRequest("Notes", null, null));

    var graph = savedWorkflows.get(0).graph();
    assertThat(savedWorkflows.get(0).origin()).isEqualTo(WorkflowOrigin.RECORDED);
    assertThat(graph.path("nodes").findValuesAsText("label")).containsExactly("Start", "Open Notepad", "End");
    assertThat(graph.path("nodes").get(1).path("config").path("target").asText()).isEqualTo(pc.id().toString());
  }

  @Test
  void aHotkeyRecordingFromAnotherPersonsOrANonRecordingDeviceIsDropped() throws Exception {
    var devices = devicesMock();
    var pc = windowsPc(devices);
    var id = UUID.randomUUID();
    service.accept(browser.id(), access.userId(), "desktop.recording.started", startedEvent(id, "x"));
    assertThat(recordingRows).isEmpty();

    service.accept(pc.id(), access.userId(), "desktop.recording.started", startedEvent(id, "x"));
    service.accept(pc.id(), UUID.randomUUID(), "desktop.recording.step", event(id, Json.read(
        "{\"seq\":0,\"action\":\"ui.click\",\"version\":1,\"input\":{}}")));
    assertThat(service.get(access, id).stepCount()).isZero();
  }

  @Test
  void aNewHotkeyRecordingSendsAnOldOpenOneToReview() throws Exception {
    var pc = windowsPc(devicesMock());
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    service.accept(pc.id(), access.userId(), "desktop.recording.started", startedEvent(first, "Excel"));
    service.accept(pc.id(), access.userId(), "desktop.recording.started", startedEvent(second, "Excel"));

    assertThat(recordingRows.get(first).getStatus()).isEqualTo("review");
    assertThat(recordingRows.get(second).getStatus()).isEqualTo("recording");
  }

  private Devices devicesMock() throws Exception {
    var field = RecordingService.class.getDeclaredField("devices");
    field.setAccessible(true);
    return (Devices) field.get(service);
  }

  // ---- Edit recording ----------------------------------------------------------------------

  private static final String ELEMENT = "{\"strategies\":[{\"by\":\"css\",\"value\":\"#x\"}],\"label\":\"Language popup\"}";

  /** A recording saved the usual way, and the workflow it became. */
  private WorkflowDto savedRecording() {
    var id = started().id();
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(0, "browser.open", "Open", "{\"url\":\"https://portal.example.com\"}", false)));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(1, "browser.click", "Book tickets", "{\"element\":" + ELEMENT + "}", false)));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(2, "browser.click", "Tamil 2D", "{\"element\":" + ELEMENT + "}", false)));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(3, "browser.getText", "Total", "{\"element\":" + ELEMENT + "}", false)));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(4, "browser.setText", "Seats", "{\"element\":" + ELEMENT + ",\"text\":\"2\"}", false)));
    service.stop(access, id);
    service.edit(access, id, List.of(new Recordings.EditOp("map", 4, null, null, "text",
        new Recordings.Input("seats", "text", null), null, null)));
    var saved = service.save(access, id, new Recordings.SaveRequest("Book", null, null));
    WorkflowDto made = savedWorkflows.get(savedWorkflows.size() - 1);
    WorkflowDto workflow = new WorkflowDto(saved.workflowId(), access.userId(), made.name(),
        made.description(), made.graph(), null, null, WorkflowOrigin.RECORDED,
        com.jizza.engine.api.enums.WorkflowStatus.DRAFT, null, 1, null, Instant.now());
    when(workflows.find(workflow.id())).thenReturn(Optional.of(workflow));
    return workflow;
  }

  @Test
  void aSavedRecordingOpensForEditingWithItsStepsAndTestValues() {
    WorkflowDto workflow = savedRecording();
    var handed = new java.util.concurrent.atomic.AtomicReference<byte[]>();
    UUID fileId = UUID.randomUUID();
    when(devices.handFile(any(), anyString(), anyString(), any())).thenAnswer(i -> {
      handed.set(i.getArgument(3));
      return fileId;
    });

    var opened = service.openForEdit(access, workflow.id(), browser.id(),
        Json.obj().put("seats", "4"));

    assertThat(opened.sourceWorkflowId()).isEqualTo(workflow.id());
    assertThat(opened.status()).isEqualTo("recording");
    var sent = org.mockito.ArgumentCaptor.forClass(com.jizza.engine.api.device.DeviceCommandRequest.class);
    verify(commands, org.mockito.Mockito.atLeastOnce()).submit(sent.capture());
    JsonNode input = sent.getValue().input();
    assertThat(input.path("mapFirst").asBoolean(true)).isFalse();
    assertThat(input.path("url").asText()).isEqualTo("https://portal.example.com");
    assertThat(input.path("edit").path("stepsFile").asText()).isEqualTo(fileId.toString());

    JsonNode file = Json.read(new String(handed.get(), java.nio.charset.StandardCharsets.UTF_8));
    List<String> actions = new ArrayList<>();
    file.path("steps").forEach(s -> actions.add(s.path("action").asText()));
    assertThat(actions).containsExactly("browser.open", "browser.click", "browser.click",
        "browser.getText", "browser.setText", "browser.close");
    JsonNode seats = file.path("steps").get(4);
    assertThat(seats.path("input").path("text").asText()).isEqualTo("4");
    assertThat(seats.path("mapped").path("text").asText()).isEqualTo("seats");
    assertThat(stepRows.stream().filter(r -> r.getRecordingId().equals(opened.id()))).hasSize(6);
  }

  /** A saved recording whose one step chooses a showtime in a theatre, both run-time inputs. */
  private WorkflowDto savedListChoice() {
    var id = started().id();
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id,
        step(0, "browser.open", "Open", "{\"url\":\"https://portal.example.com\"}", false)));
    var step = (com.fasterxml.jackson.databind.node.ObjectNode) step(1, "browser.clickItem",
        "Choose Showtime \\\"07:15 PM\\\" in \\\"PVR\\\"", "{\"element\":" + ELEMENT
            + ",\"item\":\"07:15 PM\",\"within\":{\"element\":" + ELEMENT
            + ",\"item\":\"PVR\"}}", false);
    ((com.fasterxml.jackson.databind.node.ObjectNode) step.get("meta")).set("list", Json.read(
        "{\"title\":\"Showtime\",\"withinTitle\":\"Theatre\",\"kind\":\"time\",\"count\":2,"
            + "\"groups\":[{\"label\":\"PVR\",\"items\":[\"07:15 PM\",\"10:45 PM\"]}]}"));
    service.accept(browser.id(), access.userId(), "browser.recording.step", event(id, step));
    service.stop(access, id);
    service.edit(access, id, List.of(
        new Recordings.EditOp("map", 1, null, null, "item",
            new Recordings.Input("time", "text", null), null, null),
        new Recordings.EditOp("map", 1, null, null, "within.item",
            new Recordings.Input("theatre", "text", null), null, null)));
    var saved = service.save(access, id, new Recordings.SaveRequest("Book", null, null));
    WorkflowDto made = savedWorkflows.get(savedWorkflows.size() - 1);
    return new WorkflowDto(saved.workflowId(), access.userId(), made.name(), made.description(),
        made.graph(), null, null, WorkflowOrigin.RECORDED,
        com.jizza.engine.api.enums.WorkflowStatus.DRAFT, null, 1, null, Instant.now());
  }

  private JsonNode stepsHandedForEdit(WorkflowDto workflow, JsonNode testValues) {
    when(workflows.find(workflow.id())).thenReturn(Optional.of(workflow));
    var handed = new java.util.concurrent.atomic.AtomicReference<byte[]>();
    when(devices.handFile(any(), anyString(), anyString(), any())).thenAnswer(i -> {
      handed.set(i.getArgument(3));
      return UUID.randomUUID();
    });
    service.openForEdit(access, workflow.id(), browser.id(), testValues);
    return Json.read(new String(handed.get(), java.nio.charset.StandardCharsets.UTF_8)).path("steps");
  }

  @Test
  void aListChoiceOpensForEditingWithTheItemRecorded() {
    WorkflowDto workflow = savedListChoice();
    JsonNode node = null;
    for (JsonNode n : workflow.graph().path("nodes")) {
      if ("browser.clickItem".equals(n.path("config").path("action").asText())) node = n;
    }
    // Runs still choose from the page: the inputs keep no default, the item is kept beside them.
    assertThat(node.path("config").path("inputs").path("item").asText()).isEqualTo("{{ input.time }}");
    assertThat(node.path("config").path("recorded").path("item").asText()).isEqualTo("07:15 PM");
    assertThat(node.path("config").path("recorded").path("within.item").asText()).isEqualTo("PVR");

    // An empty test value runs the step with what was recorded; a typed one with that.
    JsonNode choose = stepsHandedForEdit(workflow, Json.obj().put("time", "").put("theatre", "INOX")).get(1);
    assertThat(choose.path("input").path("item").asText()).isEqualTo("07:15 PM");
    assertThat(choose.path("input").path("within").path("item").asText()).isEqualTo("INOX");
    assertThat(choose.path("mapped").path("item").asText()).isEqualTo("time");
  }

  @Test
  void anOlderListChoiceGetsItsItemBackFromTheStepName() {
    WorkflowDto saved = savedListChoice();
    var graph = (com.fasterxml.jackson.databind.node.ObjectNode) saved.graph().deepCopy();
    graph.path("nodes").forEach(n -> ((com.fasterxml.jackson.databind.node.ObjectNode) n.path("config"))
        .remove("recorded"));
    WorkflowDto older = new WorkflowDto(saved.id(), saved.userId(), saved.name(),
        saved.description(), graph, null, null, WorkflowOrigin.RECORDED,
        com.jizza.engine.api.enums.WorkflowStatus.DRAFT, null, 1, null, Instant.now());

    JsonNode choose = stepsHandedForEdit(older, Json.obj()).get(1);
    assertThat(choose.path("input").path("item").asText()).isEqualTo("07:15 PM");
    assertThat(choose.path("input").path("within").path("item").asText()).isEqualTo("PVR");
  }

  @Test
  void editsAddAConditionAStepAndSettingsThenSaveANewVersionKeepingNodeIds() {
    WorkflowDto workflow = savedRecording();
    when(devices.handFile(any(), anyString(), anyString(), any())).thenReturn(UUID.randomUUID());
    when(workflows.saveNewVersion(any(), any())).thenAnswer(i -> new WorkflowDto(i.getArgument(0),
        access.userId(), workflow.name(), null, i.getArgument(1), null, null,
        WorkflowOrigin.RECORDED, com.jizza.engine.api.enums.WorkflowStatus.DRAFT, null, 2, null,
        Instant.now()));
    when(workflows.list(access.userId())).thenReturn(List.of());
    var opened = service.openForEdit(access, workflow.id(), browser.id(), Json.obj());
    List<Integer> seqs = new ArrayList<>();
    service.get(access, opened.id()).steps().forEach(s -> seqs.add(s.seq()));
    int bookSeq = seqs.get(1);
    int tamilSeq = seqs.get(2);
    int totalSeq = seqs.get(3);
    int next = seqs.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;

    // The language pop-up step runs only when the pop-up is there.
    var ifData = event(opened.id(), Json.read("{\"seq\":" + next + ",\"action\":\"flow.if\","
        + "\"version\":1,\"label\":\"If the language pop-up is shown\",\"input\":{\"element\":"
        + ELEMENT + ",\"timeoutMs\":2000},\"meta\":{\"sensitive\":false}}"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) ifData).put("after", bookSeq);
    service.accept(browser.id(), access.userId(), "browser.recording.step", ifData);
    var endData = event(opened.id(), Json.read("{\"seq\":" + (next + 1) + ",\"action\":\"flow.endIf\","
        + "\"version\":1,\"label\":\"End\",\"input\":{},\"meta\":{\"sensitive\":false}}"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) endData).put("after", tamilSeq);
    service.accept(browser.id(), access.userId(), "browser.recording.step", endData);
    // A new action inserted where the person was, a step turned off, another made optional.
    var waitData = event(opened.id(), step(next + 2, "browser.click", "Accept cookies",
        "{\"element\":" + ELEMENT + "}", false));
    ((com.fasterxml.jackson.databind.node.ObjectNode) waitData).put("after", seqs.get(0));
    service.accept(browser.id(), access.userId(), "browser.recording.step", waitData);
    service.accept(browser.id(), access.userId(), "browser.recording.edit", Json.obj()
        .put("recordingId", opened.id().toString()).put("op", "update").put("seq", totalSeq)
        .set("patch", Json.obj().put("disabled", true)));
    service.accept(browser.id(), access.userId(), "browser.recording.edit", Json.obj()
        .put("recordingId", opened.id().toString()).put("op", "update").put("seq", tamilSeq)
        .set("patch", Json.obj().put("optional", true)));
    service.accept(browser.id(), access.userId(), "browser.recording.stopped",
        Json.obj().put("recordingId", opened.id().toString()));

    var labels = service.get(access, opened.id()).steps().stream()
        .map(s -> s.step().path("label").asText()).toList();
    assertThat(labels).containsExactly("Open", "Accept cookies", "Book tickets",
        "If the language pop-up is shown", "Tamil 2D", "End", "Total", "Seats", "Close browser");

    var saved = service.save(access, opened.id(), new Recordings.SaveRequest(null, null, null));
    assertThat(saved.updated()).isTrue();
    assertThat(saved.version()).isEqualTo(2);
    var graph = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(workflows).saveNewVersion(org.mockito.ArgumentMatchers.eq(workflow.id()), graph.capture());
    JsonNode g = graph.getValue();
    Map<String, JsonNode> byLabel = new HashMap<>();
    g.path("nodes").forEach(n -> byLabel.put(n.path("label").asText(), n));
    JsonNode branch = byLabel.get("If the language pop-up is shown");
    assertThat(branch.path("type").asText()).isEqualTo("BRANCH");
    assertThat(byLabel.get("Tamil 2D").path("config").path("optional").asBoolean()).isTrue();
    assertThat(byLabel.get("Total").path("config").path("disabled").asBoolean()).isTrue();
    // Steps kept their node ids, so {{ nodes.step_4.text }} still reads the Total.
    assertThat(byLabel.get("Total").path("id").asText()).isEqualTo("step_4");
    assertThat(byLabel.get("Accept cookies").path("id").asText()).isNotIn("step_1", "step_2",
        "step_3", "step_4", "step_5");
    // The pop-up branch: true runs Tamil 2D, false goes straight to Total; both meet there.
    List<String> fromBranch = new ArrayList<>();
    g.path("edges").forEach(e -> {
      if (e.path("from").asText().equals(branch.path("id").asText())) {
        fromBranch.add(e.path("branch").asText() + "->" + e.path("to").asText());
      }
    });
    assertThat(fromBranch).containsExactlyInAnyOrder(
        "true->" + byLabel.get("Tamil 2D").path("id").asText(),
        "false->" + byLabel.get("Total").path("id").asText());
    // Reading it back gives the same steps.
    var again = RecordingGraph.open(g);
    assertThat(again.steps().stream().map(s -> s.path("action").asText()).toList())
        .containsExactly("browser.open", "browser.click", "browser.click", "flow.if",
            "browser.click", "flow.endIf", "browser.getText", "browser.setText", "browser.close");
    assertThat(recordingRows.get(opened.id()).getStatus()).isEqualTo("saved");
  }

  @Test
  void anEditedRecordingPastDraftCanOnlyBeSavedAsANewOne() {
    WorkflowDto draft = savedRecording();
    WorkflowDto approved = new WorkflowDto(draft.id(), draft.userId(), draft.name(), null,
        draft.graph(), null, null, WorkflowOrigin.RECORDED,
        com.jizza.engine.api.enums.WorkflowStatus.APPROVED, null, 1, null, Instant.now());
    when(workflows.find(draft.id())).thenReturn(Optional.of(approved));
    when(devices.handFile(any(), anyString(), anyString(), any())).thenReturn(UUID.randomUUID());
    var opened = service.openForEdit(access, draft.id(), browser.id(), null);
    service.stop(access, opened.id());

    assertThatThrownBy(() -> service.save(access, opened.id(), new Recordings.SaveRequest(null, null, null)))
        .isInstanceOf(ConflictException.class).hasMessageContaining("Save it as a new recording");
    var copy = service.save(access, opened.id(), new Recordings.SaveRequest("Book copy", null, null, true));
    assertThat(copy.updated()).isFalse();
    assertThat(savedWorkflows.get(savedWorkflows.size() - 1).name()).isEqualTo("Book copy");
  }

  @Test
  void aGraphForkedInTheBuilderIsRefusedForEditing() {
    var graph = Json.read("{\"nodes\":[{\"id\":\"start\",\"type\":\"TRIGGER\",\"config\":{}},"
        + "{\"id\":\"open\",\"type\":\"DEVICE_ACTION\",\"config\":{\"preset\":\"web\",\"action\":\"browser.open\",\"target\":\"sameBrowser\",\"inputs\":{\"url\":\"https://a.example.com\"}}},"
        + "{\"id\":\"b\",\"type\":\"BRANCH\",\"config\":{\"condition\":\"true\"}},"
        + "{\"id\":\"end\",\"type\":\"END\",\"config\":{}}],"
        + "\"edges\":[{\"from\":\"start\",\"to\":\"open\"},{\"from\":\"open\",\"to\":\"b\"},"
        + "{\"from\":\"b\",\"to\":\"end\",\"branch\":\"true\"},{\"from\":\"b\",\"to\":\"end\",\"branch\":\"false\"}]}");
    assertThatThrownBy(() -> RecordingGraph.open(graph)).isInstanceOf(ValidationException.class)
        .hasMessageContaining("only be changed in the builder");
  }

  @Test
  void workflowsRunningTheRecordingAreFoundAndTheirInputsUpdated() {
    WorkflowDto module = savedRecording();
    var parentGraph = Json.read("{\"nodes\":[{\"id\":\"start\",\"type\":\"TRIGGER\",\"config\":{}},"
        + "{\"id\":\"run\",\"type\":\"SUB_WORKFLOW\",\"label\":\"Book it\",\"config\":{\"workflowId\":\""
        + module.id() + "\",\"inputs\":{\"oldInput\":\"x\"}}},"
        + "{\"id\":\"end\",\"type\":\"END\",\"config\":{}}],"
        + "\"edges\":[{\"from\":\"start\",\"to\":\"run\"},{\"from\":\"run\",\"to\":\"end\"}]}");
    WorkflowDto parent = new WorkflowDto(UUID.randomUUID(), access.userId(), "Friday booking", null,
        parentGraph, null, null, WorkflowOrigin.USER_BUILT,
        com.jizza.engine.api.enums.WorkflowStatus.DRAFT, null, 3, null, Instant.now());
    when(workflows.list(access.userId())).thenReturn(List.of(module, parent));
    when(workflows.saveNewVersion(any(), any())).thenAnswer(i -> new WorkflowDto(parent.id(),
        access.userId(), parent.name(), null, i.getArgument(1), null, null,
        WorkflowOrigin.USER_BUILT, com.jizza.engine.api.enums.WorkflowStatus.DRAFT, null, 4, null,
        Instant.now()));

    var linked = service.linked(access, module.id());
    assertThat(linked).hasSize(1);
    assertThat(linked.get(0).stepLabels()).containsExactly("Book it");
    assertThat(linked.get(0).missingInputs()).containsExactly("seats");
    assertThat(linked.get(0).unknownInputs()).containsExactly("oldInput");
    assertThat(linked.get(0).canUpdate()).isTrue();

    var done = service.updateLinked(access, module.id(), List.of(parent.id()));
    assertThat(done.get(0).updated()).isTrue();
    var graph = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(workflows).saveNewVersion(org.mockito.ArgumentMatchers.eq(parent.id()), graph.capture());
    JsonNode inputs = graph.getValue().path("nodes").get(1).path("config").path("inputs");
    assertThat(inputs.has("oldInput")).isFalse();
    assertThat(inputs.has("seats")).isTrue();
  }
}
