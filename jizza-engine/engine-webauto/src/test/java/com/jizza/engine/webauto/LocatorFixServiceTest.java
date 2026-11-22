package com.jizza.engine.webauto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.procedure.ProcedureService;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.webauto.LocatorFixes;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import com.jizza.engine.persistence.entity.WebLocatorFixEntity;
import com.jizza.engine.persistence.repository.WebLocatorFixRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class LocatorFixServiceTest {

  private final Map<UUID, WebLocatorFixEntity> rows = new LinkedHashMap<>();
  private final UUID userId = UUID.randomUUID();
  private final Access access = new Access(userId, UUID.randomUUID());
  private final List<WorkflowDto> versions = new ArrayList<>();

  private WorkflowDto workflow;
  private WorkflowRegistry registry;
  private NotificationBus bus;
  private ProcedureService procedures;
  private LlmClient llm;
  private LocatorFixService service;

  @BeforeEach
  void setUp() {
    workflow = workflow(WorkflowStatus.DRAFT, 1, graph());
    registry = mock(WorkflowRegistry.class);
    when(registry.find(any())).thenAnswer(i -> Optional.of(workflow)
        .filter(w -> w.id().equals(i.getArgument(0))));
    when(registry.saveNewVersion(any(), any())).thenAnswer(i -> {
      workflow = workflow(workflow.status(), workflow.version() + 1, i.getArgument(1));
      versions.add(workflow);
      return workflow;
    });
    bus = mock(NotificationBus.class);
    procedures = mock(ProcedureService.class);
    llm = mock(LlmClient.class);
    service = service(false);
  }

  @SuppressWarnings("unchecked")
  private LocatorFixService service(boolean ranking) {
    ObjectProvider<NotificationBus> buses = mock(ObjectProvider.class);
    when(buses.getIfAvailable()).thenReturn(bus);
    ObjectProvider<ProcedureService> reviews = mock(ObjectProvider.class);
    when(reviews.getIfAvailable()).thenReturn(procedures);
    ObjectProvider<LlmClient> models = mock(ObjectProvider.class);
    when(models.getIfAvailable()).thenReturn(llm);
    return new LocatorFixService(repository(), registry, reviews, buses,
        new FixRanker(ranking, models, mock(ObjectProvider.class)));
  }

  private WebLocatorFixRepository repo;

  private WebLocatorFixRepository repository() {
    if (repo != null) return repo;
    repo = mock(WebLocatorFixRepository.class);
    when(repo.save(any())).thenAnswer(i -> store(i.getArgument(0)));
    when(repo.saveAndFlush(any())).thenAnswer(i -> store(i.getArgument(0)));
    when(repo.lockById(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<UUID>getArgument(0))));
    when(repo.findByWorkflowIdAndNodeIdAndStatus(any(), anyString(), anyString())).thenAnswer(i ->
        rows.values().stream().filter(f -> f.getWorkflowId().equals(i.getArgument(0))
            && f.getNodeId().equals(i.getArgument(1)) && f.getStatus().equals(i.getArgument(2)))
            .findFirst());
    when(repo.findByOwnerIdOrderByCreatedAtDesc(any())).thenAnswer(i -> rows.values().stream()
        .filter(f -> f.getOwnerId().equals(i.getArgument(0))).toList());
    when(repo.findByOwnerIdAndStatusOrderByCreatedAtDesc(any(), anyString())).thenAnswer(i ->
        rows.values().stream().filter(f -> f.getOwnerId().equals(i.getArgument(0))
            && f.getStatus().equals(i.getArgument(1))).toList());
    return repo;
  }

  private WebLocatorFixEntity store(WebLocatorFixEntity fix) {
    rows.put(fix.getId(), fix);
    return fix;
  }

  private final UUID workflowId = UUID.randomUUID();

  private WorkflowDto workflow(WorkflowStatus status, int version, JsonNode graph) {
    return new WorkflowDto(workflowId, userId, "Supplier invoices", null, graph, null, null,
        WorkflowOrigin.RECORDED, status, null, version, null, null);
  }

  private static JsonNode graph() {
    return Json.read("""
        {"nodes":[
          {"id":"start","type":"TRIGGER","label":"Start","config":{}},
          {"id":"open","type":"DEVICE_ACTION","label":"Open","config":{"preset":"web",
            "action":"browser.open","version":2,"inputs":{"url":"https://portal.example"}}},
          {"id":"submit","type":"DEVICE_ACTION","label":"Click Submit order","config":{
            "preset":"web","action":"browser.click","version":2,"target":"sameBrowser",
            "inputs":{"element":{"strategies":[{"by":"role","role":"button","name":"Submit order"},
              {"by":"css","value":"#submit"}],
              "frames":[{"strategies":[{"by":"css","value":"iframe#pay"}]}],
              "fingerprint":{"tag":"button","text":"Submit order"},"label":"Submit order",
              "preview":"old-preview"}}}},
          {"id":"end","type":"END","label":"Done","config":{}}],
         "edges":[{"from":"start","to":"open"},{"from":"open","to":"submit"},
                  {"from":"submit","to":"end"}]}
        """);
  }

  private static ObjectNode error(String... texts) {
    ObjectNode error = Json.obj().put("kind", "element_not_found").put("url", "https://portal.example");
    ArrayNode candidates = error.putArray("candidates");
    for (String text : texts) {
      ObjectNode c = candidates.addObject().put("score", 0.8).put("text", text);
      ObjectNode element = c.putObject("element").put("label", text + " button");
      element.putArray("strategies").addObject().put("by", "role").put("role", "button")
          .put("name", text);
      element.putObject("fingerprint").put("tag", "button").put("text", text);
    }
    return error;
  }

  private void fail(ObjectNode error) {
    service.propose(new LocatorFixes.FailedStep(userId, workflowId, 1, UUID.randomUUID(),
        "submit", error));
  }

  @Test
  void aLostElementWithCandidatesOpensAFixAndTellsThePerson() {
    fail(error("Place order", "Cancel"));

    List<LocatorFixes.Fix> open = service.list(access, "open");
    assertThat(open).hasSize(1);
    assertThat(open.get(0).nodeLabel()).isEqualTo("Click Submit order");
    assertThat(open.get(0).workflowName()).isEqualTo("Supplier invoices");
    assertThat(open.get(0).candidates()).hasSize(2);
    ArgumentCaptor<NotificationContext> told = ArgumentCaptor.forClass(NotificationContext.class);
    verify(bus).raise(eq(NotificationKind.INFO), any(), told.capture());
    assertThat(told.getValue().body())
        .isEqualTo("Step 2 of Supplier invoices could not find Submit order. Review a fix");
    assertThat(told.getValue().workflowId()).isEqualTo(workflowId);
    assertThat(told.getValue().detail().path("link").asText())
        .isEqualTo("/workflows?open=" + workflowId + "&node=submit");
    assertThat(told.getValue().detail().path("fixId").asText()).isEqualTo(open.get(0).id().toString());
  }

  private void usedBackup(String locatedJson) {
    service.backupUsed(new LocatorFixes.UsedBackup(userId, workflowId, UUID.randomUUID(),
        "submit", Json.read(locatedJson)));
  }

  @Test
  void aStepFoundByABackupWayOffersToPutThatWayFirst() {
    usedBackup("""
        [{"at":1,"strategy":{"by":"css","value":"#submit"}}]""");

    List<LocatorFixes.Fix> open = service.list(access, "open");
    assertThat(open).hasSize(1);
    JsonNode candidate = open.get(0).candidates().get(0);
    assertThat(candidate.path("backup").asBoolean()).isTrue();
    assertThat(candidate.path("text").asText())
        .isEqualTo("Found by its id \"submit\" (way 2 of 2), not by its first way, "
            + "button named \"Submit order\"");
    assertThat(candidate.path("element").path("strategies").get(0).path("value").asText())
        .isEqualTo("#submit");
    assertThat(candidate.path("element").path("strategies")).hasSize(2);
    ArgumentCaptor<NotificationContext> told = ArgumentCaptor.forClass(NotificationContext.class);
    verify(bus).raise(eq(NotificationKind.INFO), any(), told.capture());
    assertThat(told.getValue().body()).isEqualTo(
        "Step 2 of Supplier invoices found Submit order by a backup way. Make that way the first one");
    assertThat(told.getValue().detail().path("kind").asText()).isEqualTo("locator_fix");

    // Applying it saves a version where the way that worked is first and the other stays after.
    service.apply(access, open.get(0).id(), 0);
    JsonNode strategies = LocatorFixService.nodeOf(workflow.graph(), "submit").orElseThrow().path("config").path("inputs")
        .path("element").path("strategies");
    assertThat(strategies.get(0).path("value").asText()).isEqualTo("#submit");
    assertThat(strategies.get(1).path("role").asText()).isEqualTo("button");
    assertThat(strategies).hasSize(2);
  }

  @Test
  void aBackupWayIsNotedOnlyForTheStepsOwnStrategiesAndOnce() {
    usedBackup("""
        [{"at":0,"strategy":{"by":"role","role":"button","name":"Submit order"}}]""");
    usedBackup("""
        [{"at":3,"strategy":{"by":"css","value":"#somewhere-else"}}]""");
    assertThat(rows).isEmpty();

    usedBackup("""
        [{"at":1,"strategy":{"by":"css","value":"#submit"}}]""");
    usedBackup("""
        [{"at":1,"strategy":{"by":"css","value":"#submit"}}]""");
    assertThat(rows).hasSize(1);
    verify(bus, times(1)).raise(any(), any(), any());
  }

  @Test
  void theSameStepFailingAgainKeepsOneOpenFixAndDoesNotNotifyTwice() {
    fail(error("Place order"));
    fail(error("Place order now", "Cancel"));

    List<LocatorFixes.Fix> open = service.list(access, "open");
    assertThat(open).hasSize(1);
    assertThat(open.get(0).candidates()).hasSize(2);
    verify(bus, times(1)).raise(any(), any(), any());
  }

  @Test
  void otherFailuresAndMissingCandidatesOpenNothing() {
    fail(error());
    fail((ObjectNode) Json.obj().put("kind", "not_actionable").set("candidates", error("A").get("candidates")));
    ObjectNode noBundle = Json.obj().put("kind", "element_not_found");
    noBundle.putArray("candidates").addObject().put("text", "No element");
    fail(noBundle);
    service.propose(new LocatorFixes.FailedStep(UUID.randomUUID(), workflowId, 1, null, "submit",
        error("Someone else's")));

    assertThat(rows).isEmpty();
    verify(bus, never()).raise(any(), any(), any());
  }

  @Test
  void applyingSavesANewVersionWithTheChosenElementFirstAndTheOldStrategiesAsFallbacks() {
    fail(error("Place order", "Cancel"));
    UUID fixId = service.list(access, "open").get(0).id();

    LocatorFixes.Applied applied = service.apply(access, fixId, 0);

    assertThat(applied.version()).isEqualTo(2);
    JsonNode element = LocatorFixService.nodeOf(workflow.graph(), "submit").orElseThrow()
        .path("config").path("inputs").path("element");
    assertThat(element.path("strategies")).hasSize(3);
    assertThat(element.path("strategies").get(0).path("name").asText()).isEqualTo("Place order");
    assertThat(element.path("strategies").get(1).path("name").asText()).isEqualTo("Submit order");
    assertThat(element.path("strategies").get(2).path("value").asText()).isEqualTo("#submit");
    assertThat(element.path("frames")).hasSize(1);
    assertThat(element.path("label").asText()).isEqualTo("Place order button");
    assertThat(element.has("preview")).isFalse();
    assertThat(service.list(access, "open")).isEmpty();
    assertThat(service.list(access, "applied")).hasSize(1);
    assertThatThrownBy(() -> service.apply(access, fixId, 0)).isInstanceOf(ConflictException.class);
  }

  @Test
  void aPickedElementIsAppliedLikeASuggestionAndTheOldStrategiesStayAsFallbacks() {
    fail(error("Place order"));
    UUID fixId = service.list(access, "open").get(0).id();
    ObjectNode picked = Json.obj().put("label", "Confirm button")
        .put("preview", "p1").put("pagePreview", "p2");
    picked.putArray("strategies").addObject().put("by", "testid").put("value", "confirm");

    LocatorFixes.Applied applied = service.applyElement(access, fixId, picked);

    assertThat(applied.version()).isEqualTo(2);
    JsonNode element = LocatorFixService.nodeOf(workflow.graph(), "submit").orElseThrow()
        .path("config").path("inputs").path("element");
    assertThat(element.path("strategies").get(0).path("value").asText()).isEqualTo("confirm");
    assertThat(element.path("strategies").findValuesAsText("value")).contains("#submit");
    assertThat(element.has("preview")).isFalse();
    assertThat(element.has("pagePreview")).isFalse();
    assertThatThrownBy(() -> service.applyElement(access, fixId, picked))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void aPickWithoutSelectorsIsRefused() {
    fail(error("Place order"));
    UUID fixId = service.list(access, "open").get(0).id();
    assertThatThrownBy(() -> service.applyElement(access, fixId, Json.obj()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void aReviewedWorkflowGetsItsFixThroughReview() {
    workflow = workflow(WorkflowStatus.APPROVED, 3, graph());
    when(procedures.revise(eq(workflowId), any())).thenAnswer(i ->
        workflow(WorkflowStatus.PENDING_REVIEW, 4, i.getArgument(1)));
    fail(error("Place order"));

    LocatorFixes.Applied applied = service.apply(access, service.list(access, "open").get(0).id(), 0);

    assertThat(applied.version()).isEqualTo(4);
    verify(registry, never()).saveNewVersion(any(), any());
  }

  @Test
  void aStepNoLongerInTheWorkflowCannotBeFixed() {
    fail(error("Place order"));
    UUID fixId = service.list(access, "open").get(0).id();
    workflow = workflow(WorkflowStatus.DRAFT, 2, Json.read(
        "{\"nodes\":[{\"id\":\"start\",\"type\":\"TRIGGER\",\"config\":{}},"
            + "{\"id\":\"end\",\"type\":\"END\",\"config\":{}}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}"));

    assertThatThrownBy(() -> service.apply(access, fixId, 0))
        .isInstanceOf(ConflictException.class).hasMessageContaining("Dismiss this fix");
  }

  @Test
  void dismissingClosesTheFixAndAnotherAccountSeesNothing() {
    fail(error("Place order"));
    UUID fixId = service.list(access, "open").get(0).id();
    Access other = new Access(UUID.randomUUID(), UUID.randomUUID());

    assertThatThrownBy(() -> service.dismiss(other, fixId)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.apply(other, fixId, 0)).isInstanceOf(NotFoundException.class);
    assertThat(service.list(other, null)).isEmpty();

    service.dismiss(access, fixId);

    assertThat(service.list(access, "open")).isEmpty();
    assertThat(service.list(access, "dismissed")).hasSize(1);
    assertThatThrownBy(() -> service.apply(access, fixId, 0)).isInstanceOf(ConflictException.class);
    verify(registry, never()).saveNewVersion(any(), any());
  }

  @Test
  void rankingIsOffByDefaultAndNeverAsksTheModel() {
    fail(error("Cancel", "Place order"));

    assertThat(service.list(access, "open").get(0).candidates().get(0).path("text").asText())
        .isEqualTo("Cancel");
    verify(llm, never()).complete(anyString(), any(), any());
  }

  @Test
  void rankingWhenOnOnlyReordersTheCandidates() {
    when(llm.modelFor(any())).thenReturn("small");
    when(llm.complete(eq("small"), any(), any())).thenReturn(LlmResponse.text("1, 0"));
    service = service(true);

    fail(error("Cancel", "Place order"));

    JsonNode candidates = service.list(access, "open").get(0).candidates();
    assertThat(candidates).hasSize(2);
    assertThat(candidates.get(0).path("text").asText()).isEqualTo("Place order");
    assertThat(versions).as("nothing applied").isEmpty();
  }

  @Test
  void anUnreadableRankingKeepsTheBrowsersOrder() {
    ArrayNode candidates = (ArrayNode) error("A", "B").get("candidates");
    assertThat(FixRanker.reorder(candidates, "the second one")).isSameAs(candidates);
    assertThat(FixRanker.reorder(candidates, "7")).isSameAs(candidates);
    assertThat(FixRanker.reorder(candidates, "1").get(0).path("text").asText()).isEqualTo("B");
  }
}
