package com.jizza.engine.orchestrator.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSchemaValidator;
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

class AppControlToolProviderTest {

  private final UiActionRecorder recorder = new UiActionRecorder();
  private AgentEntity jizza;
  private AgentEntity specialist;
  private AppControlToolProvider provider;

  private AgentEntity agent(boolean orchestrator) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setUserId(UUID.randomUUID());
    a.setKey(orchestrator ? "jizza" : "health");
    a.setName(orchestrator ? "Jizza" : "Vita");
    a.setOrchestrator(orchestrator);
    a.setEnabled(true);
    return a;
  }

  @BeforeEach
  void setUp() {
    jizza = agent(true);
    specialist = agent(false);
    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(jizza.getId())).thenReturn(Optional.of(jizza));
    when(agents.findById(specialist.getId())).thenReturn(Optional.of(specialist));
    provider = new AppControlToolProvider(agents, recorder);
    recorder.beginTurn();
  }

  @AfterEach
  void tearDown() {
    recorder.endTurn();
  }

  private ToolResult call(String tool, String argsJson) {
    return provider.invoke(jizza.getId(), new ToolCall("c1", tool, Json.read(argsJson)));
  }

  @Test
  void onlyJizzaGetsTheAppTools() {
    assertThat(provider.toolsFor(jizza.getId())).extracting(ToolSpec::name)
        .containsExactlyInAnyOrder("app_navigate", "app_open_form", "app_ask_user", "app_set_theme");
    assertThat(provider.toolsFor(specialist.getId())).isEmpty();
    assertThat(provider.supports(specialist.getId(), "app_navigate")).isFalse();
  }

  @Test
  void recordsNavigationAndAFilledInForm() {
    assertThat(call("app_navigate", "{\"page\":\"workflows\",\"reason\":\"to build it\"}").ok())
        .isTrue();
    assertThat(call("app_open_form", """
        {"form":"new_workflow","fields":{"name":"Weekly report",
         "steps":[{"kind":"agent","agent_key":"finance","instruction":"Sum spend"}]}}
        """).ok()).isTrue();

    List<UiAction> actions = recorder.endTurn();
    assertThat(actions).extracting(UiAction::type)
        .containsExactly(UiAction.NAVIGATE, UiAction.OPEN_FORM);
    assertThat(actions.get(0).payload().path("page").asText()).isEqualTo("workflows");
    assertThat(actions.get(1).payload().path("fields").path("name").asText())
        .isEqualTo("Weekly report");
  }

  @Test
  void capsTheChoicesOnAQuestion() {
    call("app_ask_user", "{\"question\":\"Which?\",\"options\":[\"1\",\"2\",\"3\",\"4\","
        + "\"5\",\"6\",\"7\",\"8\",\"9\"]}");

    UiAction ask = recorder.endTurn().get(0);
    assertThat(ask.type()).isEqualTo(UiAction.ASK_USER);
    assertThat(ask.payload().path("options")).hasSize(8);
    assertThat(ask.payload().has("multiple")).isFalse();
  }

  @Test
  void aQuestionCanTakeSeveralAnswers() {
    call("app_ask_user", """
        {"question":"Which fields should it send?","options":["Price","Currency","Date"],
         "multiple":true}""");
    assertThat(recorder.endTurn().get(0).payload().path("multiple").asBoolean()).isTrue();
    recorder.beginTurn();

    // One option is nothing to tick.
    call("app_ask_user", "{\"question\":\"Go?\",\"options\":[\"Yes\"],\"multiple\":true}");
    assertThat(recorder.endTurn().get(0).payload().has("multiple")).isFalse();
  }

  @Test
  void configuresOnlyTheStepWhoseSettingsAreOpen() {
    assertThat(provider.toolsFor(jizza.getId())).extracting(ToolSpec::name)
        .doesNotContain("app_configure_step");
    assertThat(call("app_configure_step", "{\"stepId\":\"http_1\",\"config\":{\"url\":\"https://x\"}}")
        .ok()).isFalse();
    recorder.endTurn();

    recorder.beginTurn("http_1");
    assertThat(provider.toolsFor(jizza.getId())).extracting(ToolSpec::name)
        .contains("app_configure_step");
    assertThat(call("app_configure_step", "{\"stepId\":\"other\",\"config\":{\"url\":\"https://x\"}}")
        .ok()).isFalse();
    assertThat(call("app_configure_step", "{\"stepId\":\"http_1\",\"config\":{}}").ok()).isFalse();
    assertThat(call("app_configure_step", """
        {"stepId":"http_1","label":"Get gold rate",
         "config":{"method":"GET","url":"https://api.example.com/rates","bodyType":null}}""").ok())
        .isTrue();

    List<UiAction> actions = recorder.endTurn();
    assertThat(actions).extracting(UiAction::type).containsExactly(UiAction.CONFIGURE_STEP);
    assertThat(actions.get(0).payload().path("stepId").asText()).isEqualTo("http_1");
    assertThat(actions.get(0).payload().path("label").asText()).isEqualTo("Get gold rate");
    assertThat(actions.get(0).payload().path("config").path("url").asText())
        .isEqualTo("https://api.example.com/rates");
    // The next turn starts without a step unless one is open again.
    recorder.beginTurn();
    assertThat(recorder.stepInFocus()).isNull();
  }

  @Test
  void rejectsUnknownPagesAndActsOnNothingWithoutAScreen() {
    assertThat(call("app_navigate", "{\"page\":\"settings\"}").ok()).isFalse();
    recorder.endTurn();

    ToolResult noScreen = call("app_set_theme", "{\"theme\":\"dark\"}");
    assertThat(noScreen.ok()).isFalse();
    assertThat(recorder.endTurn()).isEmpty();
    recorder.beginTurn();
  }

  @Test
  void schemasAcceptWhatTheModelIsToldToSend() {
    ToolSpec navigate = provider.toolsFor(jizza.getId()).stream()
        .filter(s -> s.name().equals("app_navigate")).findFirst().orElseThrow();
    assertThat(ToolSchemaValidator.validate(navigate.inputSchema(),
        Json.read("{\"page\":\"tasks\"}")).valid()).isTrue();
    assertThat(ToolSchemaValidator.validate(navigate.inputSchema(),
        Json.read("{\"page\":\"nowhere\"}")).valid()).isFalse();
  }
}
