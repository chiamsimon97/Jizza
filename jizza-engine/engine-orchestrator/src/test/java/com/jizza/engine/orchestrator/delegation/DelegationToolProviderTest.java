package com.jizza.engine.orchestrator.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DelegationToolProviderTest {

  private final UUID userId = UUID.randomUUID();
  private AgentEntity jizza;
  private AgentEntity vita;
  private AgentEntity kai;
  private AgentRepository agents;
  private Agent baseAgent;
  private DelegationRecorder recorder;
  private DelegationToolProvider provider;

  private AgentEntity agent(String key, String name, boolean orchestrator) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setUserId(userId);
    a.setKey(key);
    a.setName(name);
    a.setDomain(orchestrator ? null : key);
    a.setSystemPrompt("prompt");
    a.setOrchestrator(orchestrator);
    a.setEnabled(true);
    return a;
  }

  @BeforeEach
  void setUp() {
    jizza = agent("jizza", "Jizza", true);
    vita = agent("health", "Vita", false);
    kai = agent("finance", "Kai", false);

    agents = mock(AgentRepository.class);
    when(agents.findById(jizza.getId())).thenReturn(Optional.of(jizza));
    when(agents.findById(vita.getId())).thenReturn(Optional.of(vita));
    when(agents.findById(kai.getId())).thenReturn(Optional.of(kai));
    when(agents.findByUserIdAndKey(userId, "health")).thenReturn(Optional.of(vita));
    when(agents.findByUserIdAndKey(userId, "finance")).thenReturn(Optional.of(kai));
    when(agents.findByUserIdAndKey(userId, "jizza")).thenReturn(Optional.of(jizza));
    when(agents.findByUserIdAndIsOrchestratorFalseAndEnabledTrue(userId))
        .thenReturn(List.of(vita, kai));

    baseAgent = mock(Agent.class);
    when(baseAgent.run(any())).thenAnswer(inv -> {
      AgentRequest req = inv.getArgument(0);
      AgentEntity target = req.agentId().equals(vita.getId()) ? vita : kai;
      return AgentResult.of(target.getId(), target.getName(),
          target.getName() + " says: " + req.instruction());
    });

    recorder = new DelegationRecorder();
    recorder.beginTurn();
    provider = new DelegationToolProvider(agents, baseAgent, recorder);
  }

  @Test
  void onlyTheOrchestratorIsOfferedDelegation() {
    assertThat(provider.toolsFor(jizza.getId())).extracting(ToolSpec::name)
        .containsExactly("delegate_to_specialist", "delegate_to_specialists");
    assertThat(provider.toolsFor(vita.getId())).isEmpty();
    assertThat(provider.supports(vita.getId(), "delegate_to_specialist")).isFalse();
  }

  @Test
  void inAChatWithThreadsOnJizzaReachesSpecialistsOnlyThroughThreads() {
    var withThreads = com.jizza.engine.api.chat.ChatScope.Scope.main(jizza.getUserId(), java.util.UUID.randomUUID(),
        jizza.getId(), true);
    var plain = com.jizza.engine.api.chat.ChatScope.Scope.main(jizza.getUserId(), java.util.UUID.randomUUID(),
        jizza.getId(), false);

    assertThat(com.jizza.engine.api.chat.ChatScope.run(withThreads, () -> provider.toolsFor(jizza.getId()))).isEmpty();
    assertThat(com.jizza.engine.api.chat.ChatScope.run(withThreads,
        () -> provider.supports(jizza.getId(), "delegate_to_specialist"))).isFalse();
    assertThat(com.jizza.engine.api.chat.ChatScope.run(plain, () -> provider.toolsFor(jizza.getId()))).hasSize(2);
  }

  @Test
  void theToolDescriptionListsTheAvailableSpecialistsByKeyAndName() {
    String description = provider.toolsFor(jizza.getId()).get(0).inputSchema()
        .path("properties").path("agent_key").path("description").asText();
    assertThat(description).contains("health: Vita. Use when: health").contains("finance: Kai. Use when: finance");
  }

  @Test
  void routesADomainQuestionToTheNamedSpecialist() {
    var result = provider.invoke(jizza.getId(), new ToolCall("delegate_to_specialist",
        Json.obj().put("agent_key", "health").put("task", "summarise last week's sleep")));

    assertThat(result.ok()).isTrue();
    assertThat(result.output().path("agent_name").asText()).isEqualTo("Vita");
    assertThat(result.output().path("answer").asText()).contains("summarise last week's sleep");
    assertThat(recorder.current()).extracting(DelegationRecorder.Contribution::agentName)
        .containsExactly("Vita");
  }

  @Test
  void fansOutAcrossDomainsAndReturnsEveryAnswerToJizza() {
    var delegations = Json.mapper().createArrayNode();
    delegations.addObject().put("agent_key", "health").put("task", "sleep trend");
    delegations.addObject().put("agent_key", "finance").put("task", "gym spend");

    var result = provider.invoke(jizza.getId(), new ToolCall("delegate_to_specialists",
        Json.obj().set("delegations", delegations)));

    assertThat(result.ok()).isTrue();
    assertThat(result.output()).hasSize(2);
    assertThat(result.output().findValuesAsText("agent_name")).containsExactlyInAnyOrder("Vita", "Kai");
    assertThat(recorder.current()).hasSize(2);
  }

  @Test
  void aSpecialistThatIsNotThereIsReportedRatherThanGuessedAt() {
    when(agents.findByUserIdAndKey(userId, "legal")).thenReturn(Optional.empty());

    var result = provider.invoke(jizza.getId(), new ToolCall("delegate_to_specialist",
        Json.obj().put("agent_key", "legal").put("task", "read this contract")));

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("No one with key 'legal'").contains("health: Vita");
    verify(baseAgent, never()).run(any());
  }

  @Test
  void aDisabledSpecialistIsNotRun() {
    vita.setEnabled(false);

    var result = provider.invoke(jizza.getId(), new ToolCall("delegate_to_specialist",
        Json.obj().put("agent_key", "health").put("task", "sleep trend")));

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("Vita is currently paused");
    verify(baseAgent, never()).run(any());
  }

  @Test
  void jizzaCannotDelegateToItself() {
    var result = provider.invoke(jizza.getId(), new ToolCall("delegate_to_specialist",
        Json.obj().put("agent_key", "jizza").put("task", "do it yourself")));

    assertThat(result.ok()).isFalse();
    verify(baseAgent, never()).run(any());
  }

  @Test
  void aSpecialistCannotUseTheToolEvenByCallingItDirectly() {
    var result = provider.invoke(vita.getId(), new ToolCall("delegate_to_specialist",
        Json.obj().put("agent_key", "finance").put("task", "check my balance")));

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("Only an agent with a team may hand work on");
    verify(baseAgent, never()).run(any());
  }

  @Test
  void aFailingSpecialistBecomesAReportedErrorNotALostTurn() {
    org.mockito.Mockito.doThrow(new IllegalStateException("model unavailable"))
        .when(baseAgent).run(any());

    var result = provider.invoke(jizza.getId(), new ToolCall("delegate_to_specialist",
        Json.obj().put("agent_key", "health").put("task", "sleep trend")));

    assertThat(result.ok()).isTrue();
    assertThat(result.output().path("error").asText()).contains("model unavailable");
    assertThat(recorder.current()).hasSize(1);
  }

  @Test
  void specialistsRunAsAttendedTurnsNotAutonomousOnes() {
    provider.invoke(jizza.getId(), new ToolCall("delegate_to_specialist",
        Json.obj().put("agent_key", "health").put("task", "sleep trend")));

    var captor = org.mockito.ArgumentCaptor.forClass(AgentRequest.class);
    verify(baseAgent).run(captor.capture());
    assertThat(captor.getValue().autonomous()).isFalse();
    assertThat(captor.getValue().agentId()).isEqualTo(vita.getId());
  }

  // ── Teams ───────────────────────────────────────────────────────────────────

  private AgentEntity report(String key, String name, AgentEntity manager) {
    AgentEntity a = agent(key, name, false);
    a.setCustom(true);
    a.setParentAgentId(manager.getId());
    when(agents.findById(a.getId())).thenReturn(Optional.of(a));
    when(agents.findByUserIdAndKey(userId, key)).thenReturn(Optional.of(a));
    return a;
  }

  @Test
  void aManagerOnlyReachesItsOwnDirectReports() {
    AgentEntity pm = report("c_pm", "Product manager", jizza);
    AgentEntity lead = report("c_lead", "UI lead", pm);
    when(agents.findByParentAgentIdOrderByNameAsc(pm.getId())).thenReturn(List.of(lead));
    org.mockito.Mockito.doAnswer(inv -> {
      AgentRequest req = inv.getArgument(0);
      return AgentResult.of(req.agentId(), "someone", "done: " + req.instruction());
    }).when(baseAgent).run(any());

    assertThat(provider.toolsFor(pm.getId())).extracting(ToolSpec::name)
        .contains("delegate_to_specialist");
    var sideways = provider.invoke(pm.getId(), new ToolCall("delegate_to_specialist",
        Json.obj().put("agent_key", "finance").put("task", "check my balance")));
    var down = provider.invoke(pm.getId(), new ToolCall("delegate_to_specialist",
        Json.obj().put("agent_key", "c_lead").put("task", "sketch the screen")));

    assertThat(sideways.ok()).isFalse();
    assertThat(down.ok()).isTrue();
    assertThat(provider.toolsFor(lead.getId())).as("no reports, no delegation").isEmpty();
  }

  @Test
  void jizzaMayHandWorkStraightToAnAgentDeepInATeam() {
    AgentEntity pm = report("c_pm", "Product manager", jizza);
    AgentEntity lead = report("c_lead", "UI lead", pm);
    org.mockito.Mockito.doAnswer(inv -> {
      AgentRequest req = inv.getArgument(0);
      return AgentResult.of(req.agentId(), "UI lead", "done");
    }).when(baseAgent).run(any());

    var result = provider.invoke(jizza.getId(), new ToolCall("delegate_to_specialist",
        Json.obj().put("agent_key", "c_lead").put("task", "sketch the screen")));

    assertThat(result.ok()).isTrue();
    assertThat(result.output().path("agent_name").asText()).isEqualTo("UI lead");
  }

  @Test
  void aTrialRunStaysATrialAllTheWayDownTheTeam() {
    java.util.List<AgentRequest> seen = new java.util.ArrayList<>();
    org.mockito.Mockito.doAnswer(inv -> {
      AgentRequest req = inv.getArgument(0);
      seen.add(req);
      return AgentResult.of(req.agentId(), "Vita", "ok");
    }).when(baseAgent).run(any());

    com.jizza.engine.api.agent.TrialRun.run(true, () -> provider.invoke(jizza.getId(),
        new ToolCall("delegate_to_specialist",
            Json.obj().put("agent_key", "health").put("task", "sleep trend"))));

    assertThat(seen).singleElement().satisfies(req -> assertThat(req.dryRun()).isTrue());
  }

  @Test
  void handOffsAreRecordedOnTheTeamTrail() {
    var recorded = com.jizza.engine.api.agent.TeamTrail.record(() -> provider.invoke(
        jizza.getId(), new ToolCall("delegate_to_specialist",
            Json.obj().put("agent_key", "health").put("task", "sleep trend"))));

    assertThat(recorded.steps()).singleElement().satisfies(step -> {
      assertThat(step.fromName()).isEqualTo("Jizza");
      assertThat(step.toName()).isEqualTo("Vita");
      assertThat(step.depth()).isEqualTo(1);
    });
  }
}
