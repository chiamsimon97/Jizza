package com.jizza.engine.orchestrator.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WorkflowPlanToolProviderTest {

  private final UUID userId = UUID.randomUUID();
  private final UiActionRecorder recorder = new UiActionRecorder();
  private final AutonomyGate gate = mock(AutonomyGate.class);
  private AgentEntity jizza;
  private AgentEntity kai;
  private AgentEntity vita;
  private WorkflowPlanToolProvider provider;

  private AgentEntity agent(String key, String name, boolean orchestrator, boolean enabled) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setUserId(userId);
    a.setKey(key);
    a.setName(name);
    a.setOrchestrator(orchestrator);
    a.setEnabled(enabled);
    return a;
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jizza = agent("jizza", "Jizza", true, true);
    kai = agent("finance", "Kai", false, true);
    vita = agent("health", "Vita", false, false);
    AgentRepository agents = mock(AgentRepository.class);
    for (AgentEntity a : List.of(jizza, kai, vita)) {
      when(agents.findById(a.getId())).thenReturn(Optional.of(a));
      when(agents.findByUserIdAndKey(userId, a.getKey())).thenReturn(Optional.of(a));
    }
    when(agents.findByUserIdAndIsOrchestratorFalseAndEnabledTrue(userId)).thenReturn(List.of(kai));
    ObjectProvider<AutonomyGate> gates = mock(ObjectProvider.class);
    when(gates.getIfAvailable()).thenReturn(gate);
    when(gate.evaluate(eq(kai.getId()), any(), any())).thenReturn(Decision.ask("needs ok", "kai"));
    provider = new WorkflowPlanToolProvider(agents, recorder, gates);
    recorder.beginTurn();
  }

  @AfterEach
  void tearDown() {
    recorder.endTurn();
  }

  private ToolResult propose(String argsJson) {
    return provider.invoke(jizza.getId(),
        new ToolCall("c1", WorkflowPlanToolProvider.PROPOSE, Json.read(argsJson)));
  }

  @Test
  void onlyJizzaCanProposeAPlan() {
    assertThat(provider.toolsFor(jizza.getId())).extracting(ToolSpec::name)
        .containsExactly(WorkflowPlanToolProvider.PROPOSE);
    assertThat(provider.toolsFor(kai.getId())).isEmpty();
    assertThat(provider.supports(kai.getId(), WorkflowPlanToolProvider.PROPOSE)).isFalse();
  }

  @Test
  void anOfferToSaveCarriesWhatToKeepForReuseWithSafeDefaults() {
    ToolResult result = propose("""
        {"name":"Gold rate checker",
         "steps":[{"kind":"agent","agent_key":"finance","instruction":"Fetch today's gold rate"}],
         "reuse":{"when_to_use":"When I ask for today's gold rate",
                  "examples":["check the gold rate","gold price today", " "],
                  "inputs":[{"name":"city name","description":"Where","required":true}],
                  "guardrails":{"rules":["Never buy anything"]}}}
        """);

    assertThat(result.ok()).isTrue();
    JsonNode reuse = recorder.endTurn().get(0).payload().path("reuse");
    recorder.beginTurn();
    assertThat(reuse.path("summary").asText()).isEqualTo("When I ask for today's gold rate");
    assertThat(reuse.path("examples")).hasSize(2);
    assertThat(reuse.path("inputs").get(0).path("name").asText()).isEqualTo("city_name");
    assertThat(reuse.path("guardrails").path("confirmBeforeRun").asBoolean())
        .as("asking first unless the user says otherwise").isTrue();
    assertThat(reuse.path("guardrails").path("allowScheduled").asBoolean()).isFalse();
    assertThat(reuse.path("guardrails").path("rules").get(0).asText()).isEqualTo("Never buy anything");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aToolStepRepeatsTheChatsCallWithTheChangingValuesAsInputs() {
    UUID conversationId = UUID.randomUUID();
    var journal = new com.jizza.engine.agents.registry.ServiceCallJournal();
    journal.record(conversationId, kai.getId(), "Kai", "metals_get_rate",
        Json.read("{\"metal\":\"gold\",\"place\":{\"city\":\"Chennai\"}}"));
    journal.record(conversationId, kai.getId(), "Kai", "mcp_weather", Json.obj());
    var action = new com.jizza.engine.persistence.entity.GeneratedToolEntity();
    action.setId(UUID.randomUUID());
    action.setName("metals_get_rate");
    action.setSideEffecting(false);
    var tools = mock(com.jizza.engine.persistence.repository.GeneratedToolRepository.class);
    when(tools.findEnabledForUser(userId)).thenReturn(List.of(action));
    ObjectProvider<com.jizza.engine.agents.registry.ServiceCallJournal> journals =
        mock(ObjectProvider.class);
    when(journals.getIfAvailable()).thenReturn(journal);
    ObjectProvider<AutonomyGate> gates = mock(ObjectProvider.class);
    when(gates.getIfAvailable()).thenReturn(gate);
    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(jizza.getId())).thenReturn(Optional.of(jizza));
    var withCalls = new WorkflowPlanToolProvider(agents, recorder, gates,
        new com.jizza.engine.orchestrator.library.CallSteps(journals, tools));
    var sink = new com.jizza.engine.api.activity.ActivitySink() {
      @Override public void emit(com.jizza.engine.api.activity.ActivityEvent event) {}
      @Override public boolean cancelled() { return false; }
      @Override public UUID conversationId() { return conversationId; }
    };

    ToolResult result = com.jizza.engine.api.activity.Activity.run(sink, () -> withCalls.invoke(
        jizza.getId(), new ToolCall("c1", WorkflowPlanToolProvider.PROPOSE, Json.read("""
            {"name":"Gold rate checker",
             "steps":[{"kind":"tool","call":1,"inputs":{"place.city":"city"}}],
             "reuse":{"when_to_use":"When I ask for the gold rate"}}
            """))));
    ToolResult notAStep = com.jizza.engine.api.activity.Activity.run(sink, () -> withCalls.invoke(
        jizza.getId(), new ToolCall("c2", WorkflowPlanToolProvider.PROPOSE, Json.read("""
            {"name":"Weather","steps":[{"kind":"tool","call":2}]}
            """))));

    assertThat(result.ok()).isTrue();
    assertThat(notAStep.ok()).isFalse();
    assertThat(notAStep.error()).contains("Use an agent step");
    JsonNode plan = recorder.endTurn().get(0).payload();
    recorder.beginTurn();
    JsonNode step = plan.path("steps").get(0);
    assertThat(step.path("kind").asText()).isEqualTo("tool");
    assertThat(step.path("toolId").asText()).isEqualTo(action.getId().toString());
    assertThat(step.path("arguments").path("metal").asText()).isEqualTo("gold");
    assertThat(step.path("arguments").path("place").path("city").asText())
        .isEqualTo("{{ input.city }}");
    assertThat(plan.path("reuse").path("inputs").get(0).path("name").asText())
        .as("an input a step reads is added to what the library keeps").isEqualTo("city");
    assertThat(plan.path("reuse").path("inputs").get(0).path("required").asBoolean()).isTrue();
  }

  @Test
  void recordsThePlanWithRealAgentsAndWhereItWillStopForTheUser() {
    ToolResult result = propose("""
        {"name":"Gold rate checker","description":"Checks gold each morning",
         "steps":[
           {"kind":"agent","agent_key":"finance","instruction":"Fetch today's gold rate","why":"the data"},
           {"kind":"agent","agent_key":"finance","instruction":"Buy 1g if under the limit","changes_things":true},
           {"kind":"ask","instruction":"Confirm the limit"}],
         "assumptions":["Runs at 9am Singapore time"]}
        """);

    assertThat(result.ok()).isTrue();
    List<UiAction> actions = recorder.endTurn();
    recorder.beginTurn();
    assertThat(actions).extracting(UiAction::type).containsExactly(UiAction.PROPOSE_WORKFLOW);
    JsonNode plan = actions.get(0).payload();
    assertThat(plan.path("planId").asText()).isNotBlank();
    assertThat(plan.path("name").asText()).isEqualTo("Gold rate checker");
    JsonNode steps = plan.path("steps");
    assertThat(steps).hasSize(3);
    assertThat(steps.get(0).path("agentId").asText()).isEqualTo(kai.getId().toString());
    assertThat(steps.get(0).path("agentName").asText()).isEqualTo("Kai");
    assertThat(steps.get(0).has("approval")).as("a read-only step needs no approval").isFalse();
    assertThat(steps.get(1).path("approval").asText()).isEqualTo("ask");
    assertThat(steps.get(2).path("kind").asText()).isEqualTo("ask");
    assertThat(plan.path("assumptions").get(0).asText()).isEqualTo("Runs at 9am Singapore time");
  }

  @Test
  void refusesUnknownOrDisabledAgentsAndSaysWhoIsAvailable() {
    ToolResult unknown = propose("""
        {"name":"x","steps":[{"kind":"agent","agent_key":"astrology","instruction":"Read stars"}]}
        """);
    ToolResult disabled = propose("""
        {"name":"x","steps":[{"kind":"agent","agent_key":"health","instruction":"Log sleep"}]}
        """);

    assertThat(unknown.ok()).isFalse();
    assertThat(unknown.error()).contains("finance (Kai");
    assertThat(disabled.ok()).isFalse();
    assertThat(recorder.endTurn()).isEmpty();
    recorder.beginTurn();
  }

  @Test
  void thereIsNoPlanToShowWhenTheUserIsNotInTheApp() {
    recorder.endTurn();
    ToolResult result = propose("""
        {"name":"x","steps":[{"kind":"ask","instruction":"Anything?"}]}
        """);
    assertThat(result.ok()).isFalse();
    recorder.beginTurn();
  }
}
