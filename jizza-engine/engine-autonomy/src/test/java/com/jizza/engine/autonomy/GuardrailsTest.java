package com.jizza.engine.autonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.autonomy.GuardrailContext;
import com.jizza.engine.api.autonomy.GuardrailScope;
import com.jizza.engine.api.autonomy.JizzaReviewer;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.autonomy.guardrail.GuardrailConditions;
import com.jizza.engine.autonomy.guardrail.GuardrailLedger;
import com.jizza.engine.autonomy.policy.PolicyAutonomyGate;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.AutonomyPolicyEntity;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.entity.GuardrailUsageEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.GuardrailUsageRepository;
import com.jizza.engine.persistence.repository.ServiceActionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The guardrails as the user experiences them, through the Kai example: Kai may read the
 * portfolio, asks before placing an order, never deletes, and a workflow the user approved can let
 * it place small orders on its own, inside that workflow only.
 */
class GuardrailsTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID workflowId = UUID.randomUUID();
  private final List<AutonomyPolicyEntity> rows = new ArrayList<>();
  private final List<GuardrailUsageEntity> usage = new ArrayList<>();

  private AgentEntity jizza;
  private AgentEntity kai;
  private AgentEntity vita;
  private PolicyAutonomyGate gate;
  private JizzaReviewer reviewer;
  private AuditLog audit;
  private AutonomyPolicyRepository policies;
  private ConnectionRepository connections;

  private AgentEntity agent(String key, String name, boolean orchestrator) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setUserId(userId);
    a.setKey(key);
    a.setName(name);
    a.setSystemPrompt("p");
    a.setEnabled(true);
    a.setOrchestrator(orchestrator);
    return a;
  }

  private GeneratedToolEntity tool(String name, String method, ActionType type) {
    GeneratedToolEntity t = new GeneratedToolEntity();
    t.setId(UUID.randomUUID());
    t.setConnectionId(UUID.randomUUID());
    t.setName(name);
    t.setInputSchema(Json.obj());
    t.setHttpMethod(method);
    t.setPathTemplate("/x");
    t.setSideEffecting(!"GET".equals(method));
    t.setActionType(type);
    t.setEnabled(true);
    return t;
  }

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    jizza = agent("jizza", "Jizza", true);
    kai = agent("finance", "Kai", false);
    vita = agent("health", "Vita", false);
    AgentRepository agents = mock(AgentRepository.class);
    for (AgentEntity a : List.of(jizza, kai, vita)) {
      when(agents.findById(a.getId())).thenReturn(Optional.of(a));
    }

    policies = mock(AutonomyPolicyRepository.class);
    when(policies.findByUserIdOrderByPrecedenceDesc(userId)).thenAnswer(inv -> List.copyOf(rows));
    when(policies.lockById(any())).thenAnswer(inv -> rows.stream()
        .filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());

    GeneratedToolRepository tools = mock(GeneratedToolRepository.class);
    when(tools.findEnabledForUser(userId)).thenReturn(List.of(
        tool("broker_holdings", "GET", null),
        tool("broker_place_order", "POST", ActionType.MONEY),
        tool("broker_cancel_order", "DELETE", null),
        tool("gmail_send", "POST", ActionType.SEND),
        tool("notes_update", "PUT", null)));

    GuardrailUsageRepository usageRepo = mock(GuardrailUsageRepository.class);
    when(usageRepo.save(any())).thenAnswer(inv -> {
      usage.add(inv.getArgument(0));
      return inv.getArgument(0);
    });
    when(usageRepo.countByPolicyIdAndCreatedAtAfter(any(), any())).thenAnswer(inv -> usage.stream()
        .filter(u -> u.getPolicyId().equals(inv.getArgument(0))).count());
    when(usageRepo.spentSince(any(), any())).thenAnswer(inv -> usage.stream()
        .filter(u -> u.getPolicyId().equals(inv.getArgument(0)))
        .map(u -> u.getAmount() == null ? BigDecimal.ZERO : u.getAmount())
        .reduce(BigDecimal.ZERO, BigDecimal::add));

    reviewer = mock(JizzaReviewer.class);
    ObjectProvider<JizzaReviewer> reviewers = mock(ObjectProvider.class);
    when(reviewers.getIfAvailable()).thenReturn(reviewer);
    audit = mock(AuditLog.class);

    AgentCapabilityRepository capabilities = mock(AgentCapabilityRepository.class);
    connections = mock(ConnectionRepository.class);
    gate = new PolicyAutonomyGate(policies, agents, tools, new GuardrailConditions(),
        capabilities, mock(ServiceActionRepository.class), connections,
        new GuardrailLedger(policies, usageRepo), reviewers, audit);

    // The defaults a new account gets.
    rule(null, "*:read", AutonomyRule.ALLOW, 0, null);
    rule(null, "*:side-effecting", AutonomyRule.ASK, 0, null);
    rule(null, "*:money", AutonomyRule.DENY, 0, null);
    rule(kai.getId(), "*:read", AutonomyRule.ALLOW, 10, null);
    rule(kai.getId(), "*:side-effecting", AutonomyRule.ASK, 10, null);
    rule(kai.getId(), "*:money", AutonomyRule.ASK, 10, null);
    rule(kai.getId(), "*:delete", AutonomyRule.DENY, 10, null);
  }

  private AutonomyPolicyEntity rule(UUID agentId, String toolRef, AutonomyRule rule, int precedence,
                                    JsonNode limits) {
    AutonomyPolicyEntity p = new AutonomyPolicyEntity();
    p.setId(UUID.randomUUID());
    p.setUserId(userId);
    p.setAgentId(agentId);
    p.setToolRef(toolRef);
    p.setRule(rule);
    p.setPrecedence(precedence);
    p.setLimits(limits);
    rows.add(p);
    return p;
  }

  private AutonomyPolicyEntity workflowRule(UUID agentId, String toolRef, AutonomyRule rule,
                                            Integer version, String nodeId, JsonNode limits) {
    AutonomyPolicyEntity p = rule(agentId, toolRef, rule, 0, limits);
    p.setWorkflowId(workflowId);
    p.setWorkflowVersion(version);
    p.setNodeId(nodeId);
    return p;
  }

  private static ObjectNode order(double amount) {
    return Json.obj().put("symbol", "VOO").put("amount", amount);
  }

  private Decision inWorkflow(AgentEntity agent, String tool, JsonNode args, int version,
                              String node) {
    return gate.evaluate(GuardrailContext.inWorkflow(agent.getId(), tool, args, workflowId,
        version, node, null));
  }

  // ── risk-based defaults ───────────────────────────────────────────────────

  @Test
  void kaiReadsFreelyAsksBeforeAnOrderAndNeverDeletes() {
    assertThat(gate.evaluate(kai.getId(), "broker_holdings", null).allowed()).isTrue();
    assertThat(gate.evaluate(kai.getId(), "broker_place_order", order(10)).needsPermission())
        .isTrue();
    assertThat(gate.evaluate(kai.getId(), "broker_cancel_order", null).denied()).isTrue();
    assertThat(gate.evaluate(kai.getId(), "notes_update", null).needsPermission()).isTrue();
  }

  @Test
  void noOtherAgentMovesMoneyAndNobodyElsesSendsGoUnasked() {
    assertThat(gate.evaluate(vita.getId(), "broker_place_order", order(10)).denied()).isTrue();
    assertThat(gate.evaluate(jizza.getId(), "broker_place_order", order(10)).denied()).isTrue();
    assertThat(gate.evaluate(vita.getId(), "gmail_send", null).needsPermission()).isTrue();
  }

  @Test
  void theDecisionSaysWhatKindOfActionItWas() {
    assertThat(gate.evaluate(kai.getId(), "broker_place_order", order(1)).actionType())
        .isEqualTo(ActionType.MONEY);
    assertThat(gate.evaluate(kai.getId(), "gmail_send", null).actionType())
        .isEqualTo(ActionType.SEND);
    assertThat(gate.evaluate(kai.getId(), "memory_remember", null).actionType())
        .isEqualTo(ActionType.DRAFT);
  }

  @Test
  void anActionTypeTokenOnlyMatchesThatType() {
    rule(vita.getId(), "*:send", AutonomyRule.ALLOW, 0, null);
    assertThat(gate.evaluate(vita.getId(), "gmail_send", null).allowed()).isTrue();
    assertThat(gate.evaluate(vita.getId(), "notes_update", null).needsPermission()).isTrue();
  }

  // ── a workflow the user approved ──────────────────────────────────────────

  @Test
  void aWorkflowRuleLetsKaiPlaceAnOrderInsideThatWorkflowOnly() {
    workflowRule(kai.getId(), "broker_place_order", AutonomyRule.ALLOW, 3, null, null);

    assertThat(inWorkflow(kai, "broker_place_order", order(300), 3, "buy").allowed()).isTrue();
    // Outside the workflow, and in another version of it, Kai's own default still asks.
    assertThat(gate.evaluate(kai.getId(), "broker_place_order", order(300)).needsPermission())
        .isTrue();
    assertThat(inWorkflow(kai, "broker_place_order", order(300), 4, "buy").needsPermission())
        .isTrue();
  }

  @Test
  void aStepRuleBeatsTheWorkflowRuleForThatStepOnly() {
    workflowRule(kai.getId(), "*:money", AutonomyRule.ALLOW, null, null, null);
    workflowRule(kai.getId(), "*:money", AutonomyRule.ASK, null, "rebalance", null);

    assertThat(inWorkflow(kai, "broker_place_order", order(1), 1, "buy").allowed()).isTrue();
    assertThat(inWorkflow(kai, "broker_place_order", order(1), 1, "rebalance").needsPermission())
        .isTrue();
  }

  @Test
  void anAgentStepPicksUpTheWorkflowFromTheScope() {
    workflowRule(kai.getId(), "broker_place_order", AutonomyRule.ALLOW, null, null, null);
    try (var ignored = GuardrailScope.open(workflowId, 1, "agent")) {
      assertThat(gate.evaluate(kai.getId(), "broker_place_order", order(5)).allowed()).isTrue();
    }
    assertThat(gate.evaluate(kai.getId(), "broker_place_order", order(5)).needsPermission())
        .isTrue();
  }

  // ── conditions ───────────────────────────────────────────────────────────

  @Test
  void anUnmetConditionFallsThroughToAskAndSaysWhy() {
    workflowRule(kai.getId(), "broker_place_order", AutonomyRule.ALLOW, null, null, Json.obj()
        .put("condition", "args.amount <= 500 && args.symbol =~ ['VOO','VTI']")
        .put("conditionLabel", "up to $500 of VOO or VTI"));

    assertThat(inWorkflow(kai, "broker_place_order", order(300), 1, "buy").allowed()).isTrue();

    Decision big = inWorkflow(kai, "broker_place_order", order(700), 1, "buy");
    assertThat(big.needsPermission()).isTrue();
    assertThat(big.reason()).contains("up to $500 of VOO or VTI");

    var other = Json.obj().put("symbol", "TSLA").put("amount", 10);
    assertThat(inWorkflow(kai, "broker_place_order", other, 1, "buy").needsPermission()).isTrue();
  }

  @Test
  void anUnmetConditionNeverFallsThroughToSomethingLooserThanAsk() {
    // Even where the next rule down would allow it, missing the condition means asking.
    rule(vita.getId(), "notes_update", AutonomyRule.ALLOW, 0, null);
    workflowRule(vita.getId(), "notes_update", AutonomyRule.ALLOW, null, null,
        Json.obj().put("condition", "args.size < 10"));

    Decision d = inWorkflow(vita, "notes_update", Json.obj().put("size", 50), 1, "n");
    assertThat(d.needsPermission()).isTrue();
  }

  @Test
  void aBrokenConditionIsTreatedAsNotMet() {
    workflowRule(kai.getId(), "broker_place_order", AutonomyRule.ALLOW, null, null,
        Json.obj().put("condition", "args.amount.nope.deeper > 1"));
    assertThat(inWorkflow(kai, "broker_place_order", order(1), 1, "buy").needsPermission())
        .isTrue();
  }

  @Test
  void aConditionThatCannotBeParsedIsRefusedOnSave() {
    assertThatThrownBy(() -> new GuardrailConditions()
        .validate(Json.obj().put("condition", "args.amount <=")))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void activeHoursAreHonouredInTheirTimeZone() {
    var hours = Json.obj().put("from", "09:30").put("to", "16:00").put("zone", "America/New_York");
    hours.putArray("days").add("MON").add("TUE").add("WED").add("THU").add("FRI");
    var limits = Json.obj().set("activeHours", hours);
    GuardrailConditions conditions = new GuardrailConditions();

    // Wednesday 2026-10-14 at 15:00 UTC is 11:00 in New York; at 23:00 UTC it is 19:00.
    assertThat(conditions.check(limits, null, Instant.parse("2026-10-14T15:00:00Z"))).isEmpty();
    assertThat(conditions.check(limits, null, Instant.parse("2026-10-14T23:00:00Z"))).isPresent();
    // Saturday.
    assertThat(conditions.check(limits, null, Instant.parse("2026-10-17T15:00:00Z"))).isPresent();
  }

  // ── budgets ──────────────────────────────────────────────────────────────

  @Test
  void aWeeklyBudgetStopsSmallOrdersAddingUpToABigOne() {
    var limits = Json.obj().put("condition", "args.amount <= 500");
    limits.putObject("budget").put("field", "amount").put("max", 1000).put("per", "P7D");
    workflowRule(kai.getId(), "broker_place_order", AutonomyRule.ALLOW, null, null, limits);
    GuardrailContext small = GuardrailContext.inWorkflow(kai.getId(), "broker_place_order",
        order(400), workflowId, 1, "buy", null);

    assertThat(gate.decide(small).allowed()).isTrue();
    assertThat(gate.decide(small).allowed()).isTrue();
    Decision third = gate.decide(small);
    assertThat(third.needsPermission()).isTrue();
    assertThat(third.reason()).contains("over your budget of 1000");
    assertThat(usage).hasSize(2);
  }

  @Test
  void aBudgetedCallThatDoesNotSayHowMuchItSpendsAsks() {
    var limits = Json.obj();
    limits.putObject("budget").put("field", "amount").put("max", 1000);
    workflowRule(kai.getId(), "broker_place_order", AutonomyRule.ALLOW, null, null, limits);
    Decision d = inWorkflow(kai, "broker_place_order", Json.obj().put("symbol", "VOO"), 1, "buy");
    assertThat(d.needsPermission()).isTrue();
  }

  @Test
  void aCallLimitCountsRuns() {
    var limits = Json.obj();
    limits.putObject("maxCalls").put("count", 1).put("per", "P1D");
    workflowRule(kai.getId(), "broker_place_order", AutonomyRule.ALLOW, null, null, limits);
    GuardrailContext call = GuardrailContext.inWorkflow(kai.getId(), "broker_place_order",
        order(1), workflowId, 1, "buy", null);

    assertThat(gate.decide(call).allowed()).isTrue();
    assertThat(gate.decide(call).needsPermission()).isTrue();
  }

  @Test
  void evaluatingWithoutDecidingSpendsNothing() {
    var limits = Json.obj();
    limits.putObject("maxCalls").put("count", 1);
    workflowRule(kai.getId(), "broker_place_order", AutonomyRule.ALLOW, null, null, limits);
    inWorkflow(kai, "broker_place_order", order(1), 1, "buy");
    inWorkflow(kai, "broker_place_order", order(1), 1, "buy");
    assertThat(usage).isEmpty();
  }

  // ── locks ────────────────────────────────────────────────────────────────

  @Test
  void aLockedRuleIsACeilingNothingMoreSpecificCanLoosen() {
    AutonomyPolicyEntity lock = rule(kai.getId(), "*:money", AutonomyRule.ASK, 0, null);
    lock.setLocked(true);
    workflowRule(kai.getId(), "broker_place_order", AutonomyRule.ALLOW, null, null, null);

    Decision d = inWorkflow(kai, "broker_place_order", order(1), 1, "buy");
    assertThat(d.needsPermission()).isTrue();
    assertThat(d.reason()).contains("locked");
  }

  @Test
  void aLockedRuleNeverLoosensAStricterOne() {
    AutonomyPolicyEntity lock = rule(null, "*", AutonomyRule.ALLOW, 0, null);
    lock.setLocked(true);
    assertThat(gate.evaluate(kai.getId(), "broker_cancel_order", null).denied()).isTrue();
  }

  @Test
  void anOrganizationsCeilingBindsItsConnectorsButNothingElse() {
    UUID workspaceId = UUID.randomUUID();
    ConnectionEntity shared = new ConnectionEntity();
    shared.setId(UUID.randomUUID());
    shared.setWorkspaceId(workspaceId);
    when(connections.findById(shared.getId())).thenReturn(Optional.of(shared));
    AutonomyPolicyEntity ceiling = new AutonomyPolicyEntity();
    ceiling.setId(UUID.randomUUID());
    ceiling.setUserId(UUID.randomUUID());
    ceiling.setWorkspaceId(workspaceId);
    ceiling.setToolRef("*:send");
    ceiling.setRule(AutonomyRule.DENY);
    ceiling.setLocked(true);
    when(policies.findByWorkspaceId(workspaceId)).thenReturn(List.of(ceiling));
    rule(vita.getId(), "gmail_send", AutonomyRule.ALLOW, 0, null);

    GuardrailContext viaOrganization = GuardrailContext.of(vita.getId(), "gmail_send", null)
        .withConnection(shared.getId());
    Decision d = gate.evaluate(viaOrganization);
    assertThat(d.denied()).isTrue();
    assertThat(d.matchedPolicy()).isEqualTo("organization:*:send");

    // The same tool on the user's own connector is theirs to decide.
    assertThat(gate.evaluate(vita.getId(), "gmail_send", null).allowed()).isTrue();
  }

  @Test
  void anOrganizationsCeilingIsNotItsAdminsPersonalRule() {
    AutonomyPolicyEntity ceiling = rule(null, "*", AutonomyRule.DENY, 0, null);
    ceiling.setLocked(true);
    ceiling.setWorkspaceId(UUID.randomUUID());
    assertThat(gate.evaluate(kai.getId(), "broker_holdings", null).allowed()).isTrue();
  }

  // ── let Jizza decide ─────────────────────────────────────────────────────

  @Test
  void jizzaMayDecideOnMoneyOnlyWithAConditionAndABudget() {
    workflowRule(kai.getId(), "broker_place_order", AutonomyRule.JIZZA, null, null, null);
    assertThat(inWorkflow(kai, "broker_place_order", order(1), 1, "buy").needsPermission())
        .isTrue();

    var limits = Json.obj().put("condition", "args.amount <= 500");
    limits.putObject("budget").put("max", 2000);
    rows.removeIf(r -> r.getWorkflowId() != null);
    workflowRule(kai.getId(), "broker_place_order", AutonomyRule.JIZZA, null, null, limits);
    assertThat(inWorkflow(kai, "broker_place_order", order(1), 1, "buy").needsJizza()).isTrue();
  }

  @Test
  void jizzaNeverApprovesItsOwnActions() {
    rule(jizza.getId(), "notes_update", AutonomyRule.JIZZA, 0, null);
    assertThat(gate.evaluate(jizza.getId(), "notes_update", null).needsPermission()).isTrue();
  }

  @Test
  void jizzaApprovesByNamingTheUsersInstruction() {
    rule(vita.getId(), "notes_update", AutonomyRule.JIZZA, 0, null);
    UUID instruction = UUID.randomUUID();
    when(reviewer.review(eq(userId), eq(vita.getId()), any(), any())).thenReturn(
        new JizzaReviewer.Verdict(true, instruction, "Keep my notes tidy", "covered"));

    Decision d = gate.decide(GuardrailContext.of(vita.getId(), "notes_update", null));

    assertThat(d.allowed()).isTrue();
    assertThat(d.reason()).contains("Keep my notes tidy");
    verify(audit).record(eq(userId), any(), any(), eq("guardrail.jizza_approved"),
        eq("notes_update"), any());
  }

  @Test
  void whenJizzaCannotApproveTheUserIsAsked() {
    rule(vita.getId(), "notes_update", AutonomyRule.JIZZA, 0, null);
    when(reviewer.review(any(), any(), any(), any()))
        .thenReturn(JizzaReviewer.Verdict.ask("nothing covers it"));
    assertThat(gate.decide(GuardrailContext.of(vita.getId(), "notes_update", null))
        .needsPermission()).isTrue();

    // An approval that names no instruction is not an approval.
    when(reviewer.review(any(), any(), any(), any()))
        .thenReturn(new JizzaReviewer.Verdict(true, null, null, "trust me"));
    assertThat(gate.decide(GuardrailContext.of(vita.getId(), "notes_update", null))
        .needsPermission()).isTrue();

    when(reviewer.review(any(), any(), any(), any())).thenThrow(new RuntimeException("down"));
    assertThat(gate.decide(GuardrailContext.of(vita.getId(), "notes_update", null))
        .needsPermission()).isTrue();
  }

  @Test
  void aNeverRuleIsNotSentToJizza() {
    gate.decide(GuardrailContext.of(kai.getId(), "broker_cancel_order", null));
    verify(reviewer, never()).review(any(), any(), any(), any());
  }

  // ── offering tools ───────────────────────────────────────────────────────

  @Test
  void aToolAllowedUnderAConditionIsStillOfferedButNeverAuthorisedByTheProbe() {
    rule(vita.getId(), "broker_place_order", AutonomyRule.ALLOW, 0,
        Json.obj().put("condition", "args.amount <= 5"));

    assertThat(gate.evaluate(GuardrailContext.probe(vita.getId(), "broker_place_order")).denied())
        .isFalse();
    // With real arguments over the condition it falls through to the account's never rule.
    assertThat(gate.evaluate(vita.getId(), "broker_place_order", order(50)).denied()).isTrue();
  }
}
