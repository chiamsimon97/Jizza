package com.jizza.engine.orchestrator.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityEvent;
import com.jizza.engine.api.activity.ActivitySink;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.orchestrator.ui.UiActionRecorder;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WorkflowLibraryToolProviderTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID conversationId = UUID.randomUUID();
  private final UUID workflowId = UUID.randomUUID();
  private final UiActionRecorder recorder = new UiActionRecorder();
  private WorkflowLibrary library;
  private AgentEntity jizza;
  private AgentEntity kai;
  private WorkflowLibraryToolProvider provider;
  private ReuseProfile.Guardrails guardrails = new ReuseProfile.Guardrails(true, false, null,
      List.of("Never buy anything"), null);
  private WorkflowStatus status = WorkflowStatus.APPROVED;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jizza = agent(true);
    kai = agent(false);
    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(jizza.getId())).thenReturn(Optional.of(jizza));
    when(agents.findById(kai.getId())).thenReturn(Optional.of(kai));
    library = mock(WorkflowLibrary.class);
    when(library.list(userId)).thenAnswer(inv -> List.of(match()));
    when(library.find(eq(userId), anyString(), eq(WorkflowLibrary.Use.CHAT), any(Integer.class)))
        .thenAnswer(inv -> List.of(match()));
    when(library.reuse(eq(userId), eq(workflowId), any(), eq(WorkflowLibrary.Use.CHAT), anyString(),
        anyBoolean())).thenReturn(new RunDto(UUID.randomUUID(), userId, workflowId, 1,
            "manual:library:chat", RunStatus.DONE, null, Json.obj(), Instant.now(), Instant.now(),
            List.of()));
    ObjectProvider<WorkflowLibrary> libraries = mock(ObjectProvider.class);
    when(libraries.getIfAvailable()).thenReturn(library);
    provider = new WorkflowLibraryToolProvider(agents, libraries, recorder);
  }

  @Test
  void onlyJizzaGetsTheLibraryTools() {
    assertThat(provider.toolsFor(jizza.getId())).hasSize(2);
    assertThat(provider.toolsFor(kai.getId())).isEmpty();
  }

  @Test
  void findDescribesEachCandidateWithItsGuardrails() {
    ToolResult result = inTurn("chat", () -> call(WorkflowLibraryToolProvider.FIND,
        "{\"request\":\"check the gold rate\"}"));

    var first = result.output().path("workflows").get(0);
    assertThat(first.path("workflow_id").asText()).isEqualTo(workflowId.toString());
    assertThat(first.path("guardrails").path("rules").get(0).asText()).isEqualTo("Never buy anything");
    assertThat(first.path("inputs").get(0).path("name").asText()).isEqualTo("city");
  }

  @Test
  void aWorkflowThatAsksFirstIsOfferedThenRunOnTheUsersNextMessage() {
    ActivitySink firstTurn = sink("chat");
    recorder.beginTurn();
    ToolResult offer = Activity.run(firstTurn, () -> call(WorkflowLibraryToolProvider.RUN,
        "{\"workflow_id\":\"" + workflowId + "\"}"));
    List<UiAction> shown = recorder.endTurn();

    assertThat(offer.output().path("needs_confirmation").asBoolean()).isTrue();
    assertThat(shown).singleElement().satisfies(a -> {
      assertThat(a.type()).isEqualTo(UiAction.ASK_USER);
      assertThat(a.payload().path("question").asText()).contains("Gold rate checker");
    });
    verify(library, never()).reuse(any(), any(), any(), any(), any(), anyBoolean());
    String code = offer.output().path("confirmation").asText();

    ToolResult sameTurn = Activity.run(firstTurn, () -> call(WorkflowLibraryToolProvider.RUN,
        "{\"workflow_id\":\"" + workflowId + "\",\"confirmation\":\"" + code + "\"}"));
    assertThat(sameTurn.ok()).as("Jizza cannot answer its own question").isFalse();

    recorder.beginTurn();
    ToolResult ran = Activity.run(sink("chat"), () -> call(WorkflowLibraryToolProvider.RUN,
        "{\"workflow_id\":\"" + workflowId + "\",\"confirmation\":\"" + code + "\"}"));
    List<UiAction> after = recorder.endTurn();

    assertThat(ran.ok()).isTrue();
    assertThat(ran.output().path("status").asText()).isEqualTo("done");
    verify(library).reuse(eq(userId), eq(workflowId), any(), eq(WorkflowLibrary.Use.CHAT),
        eq("chat:" + conversationId), eq(true));
    assertThat(after).extracting(UiAction::type).containsExactly(UiAction.REUSED_WORKFLOW);

    ToolResult again = Activity.run(sink("chat"), () -> call(WorkflowLibraryToolProvider.RUN,
        "{\"workflow_id\":\"" + workflowId + "\",\"confirmation\":\"" + code + "\"}"));
    assertThat(again.ok()).as("a confirmation is good for one run").isFalse();
  }

  @Test
  void aTurnNobodyTypedCannotConfirmARun() {
    String code = inTurn("chat", () -> call(WorkflowLibraryToolProvider.RUN,
        "{\"workflow_id\":\"" + workflowId + "\"}")).output().path("confirmation").asText();

    ToolResult result = inTurn("continuation", () -> call(WorkflowLibraryToolProvider.RUN,
        "{\"workflow_id\":\"" + workflowId + "\",\"confirmation\":\"" + code + "\"}"));

    assertThat(result.ok()).isFalse();
    verify(library, never()).reuse(any(), any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void anApprovedWorkflowTheUserNeedNotBeAskedAboutRunsOnTheirRequest() {
    guardrails = new ReuseProfile.Guardrails(false, false, null, List.of(), null);

    ToolResult result = inTurn("chat", () -> call(WorkflowLibraryToolProvider.RUN,
        "{\"workflow_id\":\"" + workflowId + "\",\"input\":{\"city\":\"Chennai\"}}"));

    assertThat(result.ok()).isTrue();
    verify(library).reuse(eq(userId), eq(workflowId), eq(Json.read("{\"city\":\"Chennai\"}")),
        eq(WorkflowLibrary.Use.CHAT), anyString(), eq(false));
  }

  @Test
  void aDraftIsAlwaysOfferedFirst() {
    guardrails = new ReuseProfile.Guardrails(false, false, null, List.of(), null);
    status = WorkflowStatus.DRAFT;

    ToolResult result = inTurn("chat", () -> call(WorkflowLibraryToolProvider.RUN,
        "{\"workflow_id\":\"" + workflowId + "\"}"));

    assertThat(result.output().path("needs_confirmation").asBoolean()).isTrue();
  }

  @Test
  void aWorkflowOutsideTheLibraryIsRefused() {
    ToolResult result = inTurn("chat", () -> call(WorkflowLibraryToolProvider.RUN,
        "{\"workflow_id\":\"" + UUID.randomUUID() + "\"}"));

    assertThat(result.ok()).isFalse();
  }

  private <T> T inTurn(String origin, java.util.function.Supplier<T> work) {
    recorder.beginTurn();
    try {
      return Activity.run(sink(origin), work);
    } finally {
      recorder.endTurn();
    }
  }

  private ToolResult call(String tool, String args) {
    return provider.invoke(jizza.getId(), new ToolCall("c1", tool, Json.read(args)));
  }

  private WorkflowLibrary.Match match() {
    return new WorkflowLibrary.Match(workflowId, "Gold rate checker", "Emails the gold rate",
        status, 1, new ReuseProfile("Check today's gold rate", List.of("check the gold rate"),
            List.of(new ReuseProfile.Input("city", "Where", false)), guardrails, null),
        0.7, List.of("gold", "rate"));
  }

  private ActivitySink sink(String origin) {
    return new ActivitySink() {
      @Override public void emit(ActivityEvent event) {}
      @Override public boolean cancelled() { return false; }
      @Override public String origin() { return origin; }
      @Override public UUID conversationId() { return conversationId; }
    };
  }

  private AgentEntity agent(boolean orchestrator) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setUserId(userId);
    a.setKey(orchestrator ? "jizza" : "finance");
    a.setName(orchestrator ? "Jizza" : "Kai");
    a.setOrchestrator(orchestrator);
    a.setEnabled(true);
    return a;
  }
}
