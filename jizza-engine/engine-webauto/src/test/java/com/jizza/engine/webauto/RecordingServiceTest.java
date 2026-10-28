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
    Devices devices = mock(Devices.class);
    when(devices.list(access.workspaceId(), access.userId())).thenReturn(List.of(browser));
    when(devices.find(browser.id())).thenReturn(Optional.of(browser));

    commands = mock(DeviceCommands.class);
    when(commands.submit(any())).thenAnswer(i -> command(DeviceCommandStatus.DISPATCHED));
    when(commands.find(commandId)).thenAnswer(i -> Optional.of(command(DeviceCommandStatus.DISPATCHED)));

    DeviceActionCatalog catalog = mock(DeviceActionCatalog.class);
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
    WorkflowRegistry workflows = mock(WorkflowRegistry.class);
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
}
