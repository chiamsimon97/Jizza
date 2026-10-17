package com.jizza.engine.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.autonomy.killswitch.DefaultKillSwitch;
import com.jizza.engine.autonomy.policy.PolicyAutonomyGate;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.error.AutonomyDeniedException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.AutonomyPolicyEntity;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.entity.KillSwitchEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.KillSwitchRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutonomyTest {

  private final UUID userId = UUID.randomUUID();
  private final List<AutonomyPolicyEntity> policyRows = new ArrayList<>();
  private final List<KillSwitchEntity> killRows = new ArrayList<>();

  private AgentEntity kai;
  private AgentEntity vita;
  private AutonomyPolicyRepository policies;
  private KillSwitchRepository killSwitchRepo;
  private GeneratedToolRepository tools;
  private AuditLog audit;
  private PolicyAutonomyGate gate;
  private DefaultKillSwitch killSwitch;

  private AgentEntity agent(String key, String name) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setUserId(userId);
    a.setKey(key);
    a.setName(name);
    a.setSystemPrompt("p");
    a.setEnabled(true);
    return a;
  }

  private GeneratedToolEntity tool(String name, boolean sideEffecting) {
    GeneratedToolEntity t = new GeneratedToolEntity();
    t.setId(UUID.randomUUID());
    t.setConnectionId(UUID.randomUUID());
    t.setName(name);
    t.setInputSchema(Json.obj());
    t.setHttpMethod(sideEffecting ? "POST" : "GET");
    t.setPathTemplate("/x");
    t.setSideEffecting(sideEffecting);
    t.setEnabled(true);
    return t;
  }

  @BeforeEach
  void setUp() {
    kai = agent("finance", "Kai");
    vita = agent("health", "Vita");

    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(kai.getId())).thenReturn(Optional.of(kai));
    when(agents.findById(vita.getId())).thenReturn(Optional.of(vita));
    when(agents.findByUserIdAndKey(userId, "finance")).thenReturn(Optional.of(kai));
    when(agents.findByUserIdAndKey(userId, "health")).thenReturn(Optional.of(vita));
    when(agents.findByUserIdAndKey(userId, "professional")).thenReturn(Optional.empty());

    policies = mock(AutonomyPolicyRepository.class);
    when(policies.save(any())).thenAnswer(inv -> {
      AutonomyPolicyEntity p = inv.getArgument(0);
      if (p.getId() == null) p.setId(UUID.randomUUID());
      policyRows.removeIf(r -> r.getId().equals(p.getId()));
      policyRows.add(p);
      return p;
    });
    when(policies.findByUserIdOrderByPrecedenceDesc(userId)).thenAnswer(inv -> List.copyOf(policyRows));

    killSwitchRepo = mock(KillSwitchRepository.class);
    when(killSwitchRepo.save(any())).thenAnswer(inv -> {
      KillSwitchEntity k = inv.getArgument(0);
      killRows.removeIf(r -> r.getUserId().equals(k.getUserId()));
      killRows.add(k);
      return k;
    });
    when(killSwitchRepo.findById(any())).thenAnswer(inv ->
        killRows.stream().filter(k -> k.getUserId().equals(inv.getArgument(0))).findFirst());

    tools = mock(GeneratedToolRepository.class);
    when(tools.findEnabledForUser(userId)).thenReturn(
        List.of(tool("gmail_list_messages", false), tool("gmail_send_message", true),
            tool("bank_transfer", true), tool("bank_list_transactions", false)));

    audit = mock(AuditLog.class);
    gate = new PolicyAutonomyGate(policies, agents, tools);
    killSwitch = new DefaultKillSwitch(killSwitchRepo, audit);
  }

  private void policy(UUID agentId, String toolRef, AutonomyRule rule, int precedence,
                      com.fasterxml.jackson.databind.JsonNode limits) {
    AutonomyPolicyEntity p = new AutonomyPolicyEntity();
    p.setId(UUID.randomUUID());
    p.setUserId(userId);
    p.setAgentId(agentId);
    p.setToolRef(toolRef);
    p.setRule(rule);
    p.setPrecedence(precedence);
    p.setLimits(limits);
    policyRows.add(p);
  }

  // ── the conservative default ─────────────────────────────────────────────

  @Test
  void withNoPolicyAtAllReadsAreAutomaticAndSideEffectsAsk() {
    assertThat(gate.evaluate(kai.getId(), "bank_list_transactions", null).rule())
        .isEqualTo(AutonomyRule.ALLOW);
    assertThat(gate.evaluate(kai.getId(), "bank_transfer", null).rule())
        .isEqualTo(AutonomyRule.ASK);
  }

  @Test
  void anUnrecognisedToolIsTreatedAsConsequentialRatherThanHarmless() {
    assertThat(gate.evaluate(kai.getId(), "some_tool_we_have_never_seen", null).rule())
        .isEqualTo(AutonomyRule.ASK);
  }

  @Test
  void theBuiltInReadAndRelayToolsRunFreely() {
    for (String tool : List.of("knowledge_search", "knowledge_get", "knowledge_upsert",
        "notify_user", "request_permission", "propose_workflow", "delegate_to_specialist")) {
      assertThat(gate.evaluate(kai.getId(), tool, null).rule()).as(tool)
          .isEqualTo(AutonomyRule.ALLOW);
    }
  }

  @Test
  void aSkillIsTreatedAsConsequentialBecauseItRunsAWholeWorkflow() {
    assertThat(gate.evaluate(kai.getId(), "skill_pay_bills", null).rule())
        .isEqualTo(AutonomyRule.ASK);
  }

  // ── matching and precedence ──────────────────────────────────────────────

  @Test
  void anExactToolRuleBeatsACategoryRule() {
    policy(null, PolicyAutonomyGate.ANY_SIDE_EFFECTING, AutonomyRule.ASK, 0, null);
    policy(null, "gmail_send_message", AutonomyRule.ALLOW, 0, null);

    assertThat(gate.evaluate(vita.getId(), "gmail_send_message", null).rule())
        .isEqualTo(AutonomyRule.ALLOW);
    assertThat(gate.evaluate(vita.getId(), "bank_transfer", null).rule())
        .isEqualTo(AutonomyRule.ASK);
  }

  @Test
  void aPrefixGlobMatchesAWholeConnector() {
    policy(null, "gmail_*", AutonomyRule.DENY, 0, null);

    assertThat(gate.evaluate(vita.getId(), "gmail_send_message", null).denied()).isTrue();
    assertThat(gate.evaluate(vita.getId(), "bank_list_transactions", null).denied()).isFalse();
  }

  @Test
  void higherPrecedenceWins() {
    policy(null, "bank_transfer", AutonomyRule.ALLOW, 0, null);
    policy(null, "bank_transfer", AutonomyRule.DENY, 50, null);

    assertThat(gate.evaluate(kai.getId(), "bank_transfer", null).denied()).isTrue();
  }

  @Test
  void anAgentSpecificRuleBeatsAGlobalOneAtTheSamePrecedence() {
    policy(null, "gmail_send_message", AutonomyRule.ALLOW, 0, null);
    policy(kai.getId(), "gmail_send_message", AutonomyRule.DENY, 0, null);

    assertThat(gate.evaluate(kai.getId(), "gmail_send_message", null).denied()).isTrue();
    assertThat(gate.evaluate(vita.getId(), "gmail_send_message", null).allowed()).isTrue();
  }

  @Test
  void aRuleForAnotherAgentDoesNotApply() {
    policy(vita.getId(), "bank_transfer", AutonomyRule.ALLOW, 90, null);
    assertThat(gate.evaluate(kai.getId(), "bank_transfer", null).rule())
        .isEqualTo(AutonomyRule.ASK);
  }

  // ── limits ───────────────────────────────────────────────────────────────

  @Test
  void anAllowRuleWithAValueCapRoutesAnOverLimitCallToApproval() {
    policy(kai.getId(), "bank_transfer", AutonomyRule.ALLOW, 10,
        Json.obj().put("field", "amount").put("maxValue", 5000));

    assertThat(gate.evaluate(kai.getId(), "bank_transfer", Json.obj().put("amount", 4999)).allowed())
        .isTrue();

    var over = gate.evaluate(kai.getId(), "bank_transfer", Json.obj().put("amount", 5001));
    assertThat(over.needsPermission()).isTrue();
    assertThat(over.reason()).contains("exceeds the configured limit");
  }

  @Test
  void aCapCanReachIntoANestedArgument() {
    policy(kai.getId(), "bank_transfer", AutonomyRule.ALLOW, 10,
        Json.obj().put("field", "body.amount").put("maxValue", 100));

    var args = Json.obj();
    args.putObject("body").put("amount", 250);
    assertThat(gate.evaluate(kai.getId(), "bank_transfer", args).needsPermission()).isTrue();
  }

  @Test
  void aCapIsIgnoredWhenTheArgumentIsNotThere() {
    policy(kai.getId(), "bank_transfer", AutonomyRule.ALLOW, 10,
        Json.obj().put("field", "amount").put("maxValue", 100));
    assertThat(gate.evaluate(kai.getId(), "bank_transfer", Json.obj().put("note", "x")).allowed())
        .isTrue();
  }

  // ── per-agent disable ────────────────────────────────────────────────────

  @Test
  void aDisabledAgentIsRefusedEverythingRegardlessOfPolicy() {
    policy(null, PolicyAutonomyGate.ANY, AutonomyRule.ALLOW, 99, null);
    kai.setEnabled(false);

    assertThat(gate.evaluate(kai.getId(), "bank_list_transactions", null).denied()).isTrue();
    assertThat(gate.agentEnabled(kai.getId())).isFalse();
    assertThat(gate.agentEnabled(vita.getId())).isTrue();
  }

  @Test
  void anUnknownAgentIsRefused() {
    assertThat(gate.evaluate(UUID.randomUUID(), "anything", null).denied()).isTrue();
  }

  // ── kill switch ──────────────────────────────────────────────────────────

  @Test
  void autonomyIsActiveByDefault() {
    assertThat(killSwitch.paused(userId)).isFalse();
    killSwitch.requireActive(userId);
  }

  @Test
  void throwingTheSwitchStopsAutonomousWorkAndReleasingItResumes() {
    killSwitch.setPaused(userId, true);

    assertThat(killSwitch.paused(userId)).isTrue();
    assertThatThrownBy(() -> killSwitch.requireActive(userId))
        .isInstanceOf(AutonomyDeniedException.class)
        .hasMessageContaining("Autonomy is paused");
    verify(audit).record(eq(userId), eq(ActorType.USER), any(),
        eq("autonomy.kill_switch_engaged"), any(), any());

    killSwitch.setPaused(userId, false);
    assertThat(killSwitch.paused(userId)).isFalse();
    killSwitch.requireActive(userId);
    verify(audit).record(eq(userId), eq(ActorType.USER), any(),
        eq("autonomy.kill_switch_released"), any(), any());
  }

  @Test
  void theSwitchIsReadFreshEachTimeSoOtherProcessesSeeItImmediately() {
    killSwitch.setPaused(userId, true);
    // Simulate another process flipping the row underneath us.
    killRows.get(0).setAutonomyPaused(false);
    assertThat(killSwitch.paused(userId)).isFalse();
  }

  // ── seeded defaults ──────────────────────────────────────────────────────

  /**
   * The rule set a new account is given. Which rules those are is the platform's decision and is
   * asserted there; what matters here is that the gate turns them into the Charter's behaviour.
   */
  private void seedTheConservativeDefaults() {
    policy(null, PolicyAutonomyGate.ANY_READ, AutonomyRule.ALLOW, 0, null);
    policy(null, PolicyAutonomyGate.ANY_SIDE_EFFECTING, AutonomyRule.ASK, 0, null);
    for (AgentEntity agent : List.of(kai, vita)) {
      policy(agent.getId(), PolicyAutonomyGate.ANY_READ, AutonomyRule.ALLOW, 10, null);
      policy(agent.getId(), PolicyAutonomyGate.ANY_SIDE_EFFECTING, AutonomyRule.ASK, 10, null);
    }
  }

  @Test
  void theSeededDefaultsProduceTheCharterBehaviour() {
    seedTheConservativeDefaults();

    // Finance reads freely, but money movement asks.
    assertThat(gate.evaluate(kai.getId(), "bank_list_transactions", null).allowed()).isTrue();
    assertThat(gate.evaluate(kai.getId(), "bank_transfer", null).needsPermission()).isTrue();
    // Health may read and draft, but an external send asks.
    assertThat(gate.evaluate(vita.getId(), "gmail_list_messages", null).allowed()).isTrue();
    assertThat(gate.evaluate(vita.getId(), "gmail_send_message", null).needsPermission()).isTrue();
  }
}
