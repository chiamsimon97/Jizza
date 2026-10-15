package com.jizza.engine.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityEvent;
import com.jizza.engine.api.activity.ActivitySink;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.orchestrator.delegation.DelegationRecorder;
import com.jizza.engine.orchestrator.ui.UiActionRecorder;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class JizzaOrchestratorTest {

  private final UUID userId = UUID.randomUUID();
  private AgentEntity jizza;
  private Agent baseAgent;
  private DelegationRecorder recorder;
  private final UiActionRecorder uiActions = new UiActionRecorder();
  private JizzaOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    jizza = new AgentEntity();
    jizza.setId(UUID.randomUUID());
    jizza.setUserId(userId);
    jizza.setKey("jizza");
    jizza.setName("Jizza");
    jizza.setSystemPrompt("prompt");
    jizza.setOrchestrator(true);
    jizza.setEnabled(true);

    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findByUserIdAndIsOrchestratorTrue(userId)).thenReturn(Optional.of(jizza));
    baseAgent = mock(Agent.class);
    recorder = new DelegationRecorder();
    orchestrator = new JizzaOrchestrator(agents, baseAgent, recorder, uiActions);
  }

  private Orchestrator.Turn ask(String message) {
    return orchestrator.respond(userId, new Orchestrator.TurnInput(message, null, List.of(), UUID.randomUUID()));
  }

  private void answerWith(String text, DelegationRecorder.Contribution... contributions) {
    when(baseAgent.run(any())).thenAnswer(inv -> {
      for (DelegationRecorder.Contribution c : contributions) recorder.record(c);
      return AgentResult.of(jizza.getId(), "Jizza", text);
    });
  }

  @Test
  void answersInJizzasVoice() {
    answerWith("Your sleep is trending up.");
    assertThat(ask("How did I sleep?").text()).isEqualTo("Your sleep is trending up.");
  }

  @Test
  void reportsWhichSpecialistsContributedAndWhatTheyWereAsked() {
    UUID vita = UUID.randomUUID();
    UUID kai = UUID.randomUUID();
    answerWith("Vita and Kai both looked into it.",
        new DelegationRecorder.Contribution(vita, "Vita", "sleep trend", "up 8%"),
        new DelegationRecorder.Contribution(kai, "Kai", "gym spend", "2,400 this month"));

    Orchestrator.Turn turn = ask("Is the gym worth it?");

    assertThat(turn.delegations()).extracting(Orchestrator.Delegation::agentName)
        .containsExactly("Vita", "Kai");
    assertThat(turn.delegations()).extracting(Orchestrator.Delegation::fromAgentId)
        .containsOnly(jizza.getId());
    assertThat(turn.delegations().get(0).task()).isEqualTo("sleep trend");
  }

  @Test
  void theOnlyTextTheUserSeesIsJizzasOwn() {
    answerWith("Here is the short version.",
        new DelegationRecorder.Contribution(UUID.randomUUID(), "Vita",
            "sleep trend", "RAW SPECIALIST OUTPUT that should never surface"));
    assertThat(ask("How did I sleep?").text()).doesNotContain("RAW SPECIALIST OUTPUT");
  }

  @Test
  void passesTheHistoryItIsGivenAndTheCurrentMessageSeparately() {
    answerWith("second answer");
    orchestrator.respond(userId, new Orchestrator.TurnInput("second question", null,
        List.of(Msg.user("first question"), Msg.assistant("first answer")), UUID.randomUUID()));

    ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
    Mockito.verify(baseAgent).run(captor.capture());
    assertThat(captor.getValue().instruction()).isEqualTo("second question");
    assertThat(captor.getValue().history()).extracting(Msg::text)
        .containsExactly("first question", "first answer");
    assertThat(captor.getValue().traceId()).startsWith("chat_turn:");
  }

  @Test
  void anIncognitoTurnHasNoMessageToStampMemoryReadsWith() {
    answerWith("ok");
    orchestrator.respond(userId, new Orchestrator.TurnInput("hi", null, List.of(), null));
    ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
    Mockito.verify(baseAgent).run(captor.capture());
    assertThat(captor.getValue().traceId()).isNull();
  }

  @Test
  void aUserWithoutAnOrchestratorAgentIsARecognisableError() {
    assertThatThrownBy(() -> orchestrator.respond(UUID.randomUUID(),
        new Orchestrator.TurnInput("hello", null, List.of(), null)))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("Orchestrator agent for user");
  }

  @Test
  void anEmptyModelReplyStillProducesSomethingHonest() {
    when(baseAgent.run(any())).thenReturn(AgentResult.of(jizza.getId(), "Jizza", "  "));
    assertThat(ask("hello?").text()).isEqualTo("I could not put together an answer for that.");
  }

  @Test
  void theTurnScopedRecorderDoesNotLeakBetweenTurns() {
    answerWith("one", new DelegationRecorder.Contribution(UUID.randomUUID(), "Vita", "t", "r"));
    ask("first");
    answerWith("two");
    assertThat(ask("second").delegations()).isEmpty();
  }

  @Test
  void carriesScreenActionsAndReportsThemToTheJob() {
    when(baseAgent.run(any())).thenAnswer(inv -> {
      AgentRequest req = inv.getArgument(0);
      assertThat(req.instruction()).contains("[App context: the user is on the 'tasks' page");
      uiActions.record(new UiAction(UiAction.NAVIGATE, Json.obj().put("page", "workflows")));
      return AgentResult.of(jizza.getId(), "Jizza", "Opened Workflows.");
    });
    List<ActivityEvent> events = new ArrayList<>();
    ActivitySink sink = new ActivitySink() {
      public void emit(ActivityEvent event) { events.add(event); }
      public boolean cancelled() { return false; }
    };

    Orchestrator.Turn turn = Activity.run(sink, () -> orchestrator.respond(userId,
        new Orchestrator.TurnInput("Make me a workflow", "tasks", List.of(), UUID.randomUUID())));

    assertThat(turn.actions()).extracting(UiAction::type).containsExactly(UiAction.NAVIGATE);
    assertThat(events).extracting(ActivityEvent::type).containsExactly(ActivityTypes.UI_ACTION);
  }

  @Test
  void opensNoScreenTurnWhenTheMessageDidNotComeFromTheApp() {
    when(baseAgent.run(any())).thenAnswer(inv -> {
      assertThat(uiActions.active()).isFalse();
      return AgentResult.of(jizza.getId(), "Jizza", "Hello.");
    });
    assertThat(ask("Hi").actions()).isEmpty();
    assertThat(uiActions.active()).isFalse();
  }
}
