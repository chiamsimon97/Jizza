package com.jizza.engine.orchestrator.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class RunWorkflowShortcutTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID conversationId = UUID.randomUUID();
  private final List<WorkflowLibrary.Match> workflows = new ArrayList<>();
  private WorkflowLibrary library;
  private RunWorkflowShortcut shortcut;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    library = mock(WorkflowLibrary.class);
    when(library.runnable(userId)).thenAnswer(inv -> workflows);
    when(library.search(eq(userId), anyString(), anyInt())).thenReturn(List.of());
    when(library.reuse(eq(userId), any(), any(), eq(WorkflowLibrary.Use.CHAT), anyString(), anyBoolean()))
        .thenAnswer(inv -> new RunDto(UUID.randomUUID(), userId, inv.getArgument(1), 1,
            "manual:library:chat", RunStatus.RUNNING, null, Json.obj(), Instant.now(), null, List.of()));
    ObjectProvider<WorkflowLibrary> libraries = mock(ObjectProvider.class);
    when(libraries.getIfAvailable()).thenReturn(library);
    shortcut = new RunWorkflowShortcut(libraries);
  }

  private WorkflowLibrary.Match add(String name, ReuseProfile.Input... inputs) {
    ReuseProfile profile = new ReuseProfile(null, List.of(), List.of(inputs),
        ReuseProfile.Guardrails.DEFAULT, null);
    WorkflowLibrary.Match m = new WorkflowLibrary.Match(UUID.randomUUID(), name, null,
        WorkflowStatus.DRAFT, 1, profile, 0, List.of());
    workflows.add(m);
    return m;
  }

  private Orchestrator.Turn send(String text) {
    return shortcut.handle(userId, conversationId, text).orElseThrow();
  }

  @Test
  void leavesOrdinaryMessagesToJizza() {
    assertThat(shortcut.handle(userId, conversationId, "I would like you to execute Simon workflow")).isEmpty();
    assertThat(shortcut.handle(userId, conversationId, "/runner")).isEmpty();
    assertThat(shortcut.handle(userId, conversationId, "/help")).isEmpty();
  }

  @Test
  void runsTheNamedWorkflowWithoutAskingAndWithoutTheModel() {
    WorkflowLibrary.Match simon = add("Simon workflow");
    add("Ticket booking");

    Orchestrator.Turn turn = send("/run simon WORKFLOW");

    verify(library).reuse(eq(userId), eq(simon.workflowId()), any(), eq(WorkflowLibrary.Use.CHAT),
        eq("chat:" + conversationId), eq(true));
    assertThat(turn.text()).startsWith("Started **Simon workflow**");
    assertThat(turn.inputTokens()).isZero();
    assertThat(turn.actions()).singleElement().satisfies(a -> {
      assertThat(a.type()).isEqualTo(UiAction.REUSED_WORKFLOW);
      assertThat(a.payload().path("name").asText()).isEqualTo("Simon workflow");
      assertThat(a.payload().path("runId").asText()).isNotBlank();
    });
  }

  @Test
  void aPartOfTheNameRunsTheOnlyWorkflowThatHasIt() {
    WorkflowLibrary.Match simon = add("Simon workflow");
    add("Ticket booking");

    send("/run simon");

    verify(library).reuse(eq(userId), eq(simon.workflowId()), any(), any(), anyString(), eq(true));
  }

  @Test
  void severalPossibleWorkflowsAreOfferedAsChoices() {
    add("Book movie tickets");
    add("Book train tickets");

    Orchestrator.Turn turn = send("/run book");

    verify(library, never()).reuse(any(), any(), any(), any(), anyString(), anyBoolean());
    assertThat(turn.actions()).singleElement().satisfies(a -> {
      assertThat(a.type()).isEqualTo(UiAction.ASK_USER);
      assertThat(a.payload().path("options").toString())
          .contains("/run Book movie tickets", "/run Book train tickets");
    });
  }

  @Test
  void theCommandAloneListsTheWorkflows() {
    add("Simon workflow");

    Orchestrator.Turn turn = send("/run");

    assertThat(turn.actions()).singleElement()
        .satisfies(a -> assertThat(a.payload().path("options").get(0).asText()).isEqualTo("/run Simon workflow"));
  }

  @Test
  void unknownNamesSaySoAndOfferWhatThereIs() {
    add("Simon workflow");

    Orchestrator.Turn turn = send("/run gold rate");

    assertThat(turn.text()).contains("couldn't find a workflow called \"gold rate\"");
    verify(library, never()).reuse(any(), any(), any(), any(), anyString(), anyBoolean());
  }

  @Test
  void inputsGoOnTheLinesBelowAndMissingOnesAreAskedFor() {
    WorkflowLibrary.Match booking = add("Ticket booking",
        new ReuseProfile.Input("movie", "Which movie", true), new ReuseProfile.Input("seats", null, false));

    Orchestrator.Turn asked = send("/run Ticket booking");
    assertThat(asked.text()).contains("needs one value", "/run Ticket booking\nmovie: \nseats: ", "**movie**: Which movie");
    verify(library, never()).reuse(any(), any(), any(), any(), anyString(), anyBoolean());

    send("/run Ticket booking\nMovie: Ramayana\nseats: 2");
    ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
    verify(library).reuse(eq(userId), eq(booking.workflowId()), input.capture(), any(), anyString(), eq(true));
    assertThat(input.getValue().path("movie").asText()).isEqualTo("Ramayana");
    assertThat(input.getValue().path("seats").asText()).isEqualTo("2");
  }

  @Test
  void aRefusedRunIsExplained() {
    WorkflowLibrary.Match simon = add("Simon workflow");
    when(library.reuse(eq(userId), eq(simon.workflowId()), any(), any(), anyString(), anyBoolean()))
        .thenThrow(new ValidationException("'Simon workflow' has already been reused 3 times today, its limit"));

    Orchestrator.Turn turn = send("/run Simon workflow");

    assertThat(turn.text()).isEqualTo("I couldn't run \"Simon workflow\": 'Simon workflow' has already been reused 3 times today, its limit");
    assertThat(turn.actions()).isEmpty();
  }
}
