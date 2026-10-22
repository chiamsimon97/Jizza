package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.RunStepStatus;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SagaWorkflowEngineTest {

  private WorkflowNode node(String id, NodeType type, String configJson) {
    return new WorkflowNode(id, type, id,
        configJson == null ? null : Json.read(configJson), null);
  }

  private WorkflowNode retrying(String id, NodeType type, String configJson, int maxAttempts) {
    return new WorkflowNode(id, type, id, configJson == null ? null : Json.read(configJson),
        new WorkflowNode.RetryPolicy(maxAttempts, 1, 1.0, 100));
  }

  private WorkflowEdge edge(String from, String to) { return new WorkflowEdge(from, to, null); }

  // ── a straightforward three-step workflow ────────────────────────────────

  private EngineHarness linear() {
    return new EngineHarness(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("double", NodeType.TRANSFORM, "{\"expression\":\"input.amount * 2\"}"),
            node("summarise", NodeType.AGENT_STEP,
                "{\"agentId\":\"" + UUID.randomUUID() + "\",\"instruction\":\"summarise\"}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "double"), edge("double", "summarise"), edge("summarise", "finish"))));
  }

  @Test
  void runsAGraphToCompletionCheckpointingEveryNode() {
    EngineHarness harness = linear();

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("amount", 21), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(run.steps()).extracting(com.jizza.engine.api.dto.RunDto.StepDto::nodeId)
        .containsExactlyInAnyOrder("start", "double", "summarise", "finish");
    assertThat(run.steps()).allSatisfy(step ->
        assertThat(step.status()).isEqualTo(RunStepStatus.DONE));
  }

  @Test
  void eachNodeSeesTheOutputOfTheOnesBeforeIt() {
    EngineHarness harness = linear();
    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("amount", 21), "manual");

    assertThat(harness.step(run.id(), "double").orElseThrow().getOutput().asInt()).isEqualTo(42);
  }

  // ── resume from the last completed node ──────────────────────────────────

  @Test
  void aRunKilledMidFlightResumesFromItsLastCompletedNode() {
    EngineHarness harness = linear();
    // The agent step dies the way a killed process would: the node never checkpoints.
    harness.agentStepBehaviour = nodeId -> {
      throw new IllegalStateException("the process died here");
    };
    harness.agentStepRuns.clear();

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("amount", 21), "manual");
    assertThat(run.status()).isNotEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "double").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.DONE);

    // A fresh process picks the run up. It must not re-run the nodes that already completed.
    harness.agentStepBehaviour = nodeId -> NodeExecutor.Outcome.completed(
        Json.obj().put("text", "recovered"));
    harness.run(run.id()).setStatus(RunStatus.RUNNING);
    var resumed = harness.engine.resumeAfterRestart(run.id());

    assertThat(resumed.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "double").orElseThrow().getOutput().asInt())
        .as("the earlier node kept its original result rather than being recomputed")
        .isEqualTo(42);
  }

  // ── exactly-once side effects ────────────────────────────────────────────

  /**
   * The tool id is minted by the harness, so the graph is attached afterwards; only the workflow
   * row's graph is read once a run starts.
   */
  private EngineHarness sideEffectHarness() {
    EngineHarness harness = new EngineHarness(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null), node("finish", NodeType.END, null)),
        List.of(edge("start", "finish"))));
    harness.workflow.setGraph(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("send", NodeType.CONNECTOR_TOOL,
                "{\"toolId\":\"" + harness.sendEmailTool.getId() + "\"}"),
            node("after", NodeType.AGENT_STEP,
                "{\"agentId\":\"" + UUID.randomUUID() + "\",\"instruction\":\"confirm\"}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "send"), edge("send", "after"), edge("after", "finish"))).toJson());
    return harness;
  }

  @Test
  void aServiceActionStepFillsItsArgumentsFromTheRunsInput() {
    EngineHarness harness = new EngineHarness(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null), node("finish", NodeType.END, null)),
        List.of(edge("start", "finish"))));
    harness.workflow.setGraph(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("send", NodeType.CONNECTOR_TOOL, "{\"toolId\":\"" + harness.sendEmailTool.getId()
                + "\",\"arguments\":{\"to\":\"{{ input.email }}\",\"copies\":\"{{ input.copies }}\","
                + "\"subject\":\"Rate for {{ input.city }}\",\"body\":\"fixed\"}}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "send"), edge("send", "finish"))).toJson());

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("email", "kai@example.com").put("copies", 2).put("city", "Chennai"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.lastToolArgs.path("to").asText()).isEqualTo("kai@example.com");
    assertThat(harness.lastToolArgs.path("copies").isNumber())
        .as("a single placeholder keeps the type of what it gives").isTrue();
    assertThat(harness.lastToolArgs.path("subject").asText()).isEqualTo("Rate for Chennai");
    assertThat(harness.lastToolArgs.path("body").asText()).isEqualTo("fixed");
  }

  @Test
  void aSideEffectingNodeIsSentOnceEvenAcrossACrashAndResume() {
    EngineHarness harness = sideEffectHarness();
    harness.agentStepBehaviour = nodeId -> {
      throw new IllegalStateException("crash after the email went out");
    };
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.toolInvocations).isEqualTo(1);
    assertThat(harness.step(run.id(), "send").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.DONE);

    harness.agentStepBehaviour = nodeId -> NodeExecutor.Outcome.completed(Json.obj());
    harness.run(run.id()).setStatus(RunStatus.RUNNING);
    harness.engine.resumeAfterRestart(run.id());

    assertThat(harness.toolInvocations)
        .as("the email must not be sent a second time on resume").isEqualTo(1);
  }

  @Test
  void anAskPolicyStopsTheRunBeforeTheSideEffectAndResumesOnApproval() {
    EngineHarness harness = sideEffectHarness();
    harness.toolRule = AutonomyRule.ASK;

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.toolInvocations).isZero();
    assertThat(harness.raised).extracting(com.jizza.engine.api.dto.NotificationDto::kind)
        .contains(NotificationKind.PERMISSION_REQUEST);

    // The rule still says ask: approving must be enough on its own, without asking again.
    var resumed = harness.engine.resume(run.id(), Json.obj().put("approved", true));

    assertThat(resumed.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.toolInvocations).isEqualTo(1);
    assertThat(harness.raised).extracting(com.jizza.engine.api.dto.NotificationDto::kind)
        .as("approving must not raise a second request for the same step")
        .containsOnlyOnce(NotificationKind.PERMISSION_REQUEST);
  }

  @Test
  void anApprovalDoesNotOverrideANeverRule() {
    EngineHarness harness = sideEffectHarness();
    harness.toolRule = AutonomyRule.ASK;
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    // The user tightened the rule to never while the step was waiting.
    harness.toolRule = AutonomyRule.DENY;
    var resumed = harness.engine.resume(run.id(), Json.obj().put("approved", true));

    // The step fails rather than running, and the run stops to ask what to do next.
    assertThat(resumed.status()).isNotEqualTo(RunStatus.DONE);
    assertThat(harness.toolInvocations).isZero();
    assertThat(harness.raised).extracting(com.jizza.engine.api.dto.NotificationDto::kind)
        .contains(NotificationKind.INSTRUCTION_REQUIRED);
  }

  @Test
  void aStepLeftToJizzaPausesWhenNoReviewerApprovesIt() {
    EngineHarness harness = sideEffectHarness();
    harness.toolRule = AutonomyRule.JIZZA;

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.toolInvocations).isZero();
  }

  @Test
  void decliningPermissionFailsTheRunRatherThanRunningAnyway() {
    EngineHarness harness = sideEffectHarness();
    harness.toolRule = AutonomyRule.ASK;
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    var declined = harness.engine.resume(run.id(), Json.obj().put("approved", false));

    assertThat(declined.status()).isEqualTo(RunStatus.FAILED);
    assertThat(harness.toolInvocations).isZero();
  }

  @Test
  void aDenyPolicyStopsTheNodeOutright() {
    EngineHarness harness = sideEffectHarness();
    harness.toolRule = AutonomyRule.DENY;

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.toolInvocations).isZero();
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
  }

  // ── human intervention ───────────────────────────────────────────────────

  private EngineHarness withIntervention() {
    return new EngineHarness(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("ask", NodeType.HUMAN_INTERVENTION,
                "{\"prompt\":\"Which supplier should I use?\"}"),
            node("use", NodeType.TRANSFORM,
                "{\"expression\":\"instructions.ask\"}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "ask"), edge("ask", "use"), edge("use", "finish"))));
  }

  @Test
  void anInterventionNodePausesTheRunAndAsksTheUser() {
    EngineHarness harness = withIntervention();

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(run.currentNode()).isEqualTo("ask");
    assertThat(harness.raised).hasSize(1);
    assertThat(harness.raised.get(0).kind()).isEqualTo(NotificationKind.INSTRUCTION_REQUIRED);
    assertThat(harness.raised.get(0).body()).isEqualTo("Which supplier should I use?");
    assertThat(harness.raised.get(0).runId()).isEqualTo(run.id());
  }

  @Test
  void theRunContinuesOnTheSubmittedInstructionAndFeedsItDownstream() {
    EngineHarness harness = withIntervention();
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    var resumed = harness.engine.resume(run.id(), Json.obj().put("instruction", "Use Acme Ltd"));

    assertThat(resumed.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "use").orElseThrow().getOutput().asText())
        .isEqualTo("Use Acme Ltd");
  }

  @Test
  void aPausedRunSurvivesARestartUntouched() {
    EngineHarness harness = withIntervention();
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    // A restart sweep only resumes RUNNING runs; a paused one is left exactly as it was.
    harness.engine.resumeAfterRestart(run.id());
    var reread = harness.engine.get(run.id());

    assertThat(reread.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(reread.currentNode()).isEqualTo("ask");

    var resumed = harness.engine.resume(run.id(), Json.obj().put("instruction", "Use Acme Ltd"));
    assertThat(resumed.status()).isEqualTo(RunStatus.DONE);
  }

  @Test
  void resumingWithoutAnInstructionIsRejected() {
    EngineHarness harness = withIntervention();
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> harness.engine.resume(run.id(), Json.obj()))
        .isInstanceOf(com.jizza.engine.api.error.ValidationException.class)
        .hasMessageContaining("instruction");
  }

  // ── retries, dead-letter and intervention on failure ─────────────────────

  private EngineHarness failing(String onFailure, int maxAttempts) {
    return new EngineHarness(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            retrying("flaky", NodeType.AGENT_STEP,
                "{\"agentId\":\"" + UUID.randomUUID() + "\",\"instruction\":\"try\","
                    + "\"onFailure\":\"" + onFailure + "\"}", maxAttempts),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "flaky"), edge("flaky", "finish"))));
  }

  @Test
  void aRetryableFailureIsRetriedUpToThePolicyThenRaisesAnIntervention() {
    EngineHarness harness = failing("intervene", 3);
    harness.agentStepBehaviour = nodeId -> NodeExecutor.Outcome.retryable("upstream 503");

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.agentStepRuns.get("flaky")).isEqualTo(3);
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.raised).extracting(com.jizza.engine.api.dto.NotificationDto::kind)
        .contains(NotificationKind.INSTRUCTION_REQUIRED);
    assertThat(harness.raised.get(0).body()).contains("upstream 503").contains("3 attempts");
  }

  @Test
  void aNodeThatAsksToDeadLetterDoesSoRatherThanBotheringTheUser() {
    EngineHarness harness = failing("dead_letter", 2);
    harness.agentStepBehaviour = nodeId -> NodeExecutor.Outcome.retryable("upstream 503");

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(harness.run(run.id()).isDeadLettered()).isTrue();
    assertThat(harness.raised).isEmpty();
  }

  @Test
  void aRetrySucceedingOnTheSecondAttemptCompletesTheRun() {
    EngineHarness harness = failing("intervene", 3);
    harness.agentStepBehaviour = nodeId -> harness.agentStepRuns.getOrDefault(nodeId, 0) < 2
        ? NodeExecutor.Outcome.retryable("transient")
        : NodeExecutor.Outcome.completed(Json.obj().put("text", "worked"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.agentStepRuns.get("flaky")).isEqualTo(2);
  }

  @Test
  void aPermanentFailureIsNotRetried() {
    EngineHarness harness = failing("dead_letter", 5);
    harness.agentStepBehaviour = nodeId -> NodeExecutor.Outcome.permanentFailure("bad config");

    harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.agentStepRuns.get("flaky")).isEqualTo(1);
  }

  // ── kill switch ──────────────────────────────────────────────────────────

  @Test
  void theKillSwitchHaltsTheRunAtTheNextCheckpointAndItResumesWhenReleased() {
    EngineHarness harness = linear();
    harness.agentStepRuns.clear();
    harness.paused = true;

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("amount", 21), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
    assertThat(run.currentNode()).isEqualTo("start");
    assertThat(harness.agentStepRuns).isEmpty();

    harness.paused = false;
    var resumed = harness.engine.resumeAfterRestart(run.id());

    assertThat(resumed.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.agentStepRuns.get("summarise")).isEqualTo(1);
  }

  @Test
  void aPauseMidRunLeavesCompletedNodesCheckpointedSoNothingIsRepeated() {
    EngineHarness harness = linear();
    harness.agentStepBehaviour = nodeId -> {
      harness.paused = true;   // the switch is thrown while this node runs
      return NodeExecutor.Outcome.completed(Json.obj().put("text", "done"));
    };

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("amount", 21), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
    assertThat(harness.step(run.id(), "summarise").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.DONE);

    harness.paused = false;
    harness.agentStepBehaviour = nodeId -> NodeExecutor.Outcome.completed(Json.obj());
    harness.engine.resumeAfterRestart(run.id());

    assertThat(harness.agentStepRuns.get("summarise"))
        .as("the completed node is not run again after the pause").isEqualTo(1);
  }

  // ── branching ────────────────────────────────────────────────────────────

  private EngineHarness branching() {
    return new EngineHarness(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("big", NodeType.BRANCH, "{\"condition\":\"input.total > 1000\"}"),
            node("escalate", NodeType.TRANSFORM, "{\"expression\":\"'escalated'\"}"),
            node("file", NodeType.TRANSFORM, "{\"expression\":\"'filed'\"}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "big"),
            new WorkflowEdge("big", "escalate", "true"),
            new WorkflowEdge("big", "file", "false"),
            edge("escalate", "finish"), edge("file", "finish"))));
  }

  @Test
  void aBranchTakesTheOutletItsConditionSelects() {
    EngineHarness high = branching();
    high.engine.start(high.workflow.getId(), 1, Json.obj().put("total", 5000), "manual");
    assertThat(high.step(high.runRows.get(0).getId(), "escalate")).isPresent();
    assertThat(high.step(high.runRows.get(0).getId(), "file"))
        .as("the path not taken is recorded as skipped, never run")
        .hasValueSatisfying(step -> assertThat(step.getStatus()).isEqualTo(RunStepStatus.SKIPPED));

    EngineHarness low = branching();
    low.engine.start(low.workflow.getId(), 1, Json.obj().put("total", 50), "manual");
    assertThat(low.step(low.runRows.get(0).getId(), "file")).isPresent();
    assertThat(low.step(low.runRows.get(0).getId(), "escalate"))
        .hasValueSatisfying(step -> assertThat(step.getStatus()).isEqualTo(RunStepStatus.SKIPPED));
    assertThat(low.runRows.get(0).getStatus())
        .as("the merge after the branch runs once, without waiting for the side not taken")
        .isEqualTo(RunStatus.DONE);
  }

  // ── approval gate on starting a run ──────────────────────────────────────

  @Test
  void anUnapprovedWorkflowCannotBeRunUnattendedButCanBeTestedManually() {
    EngineHarness harness = linear();
    harness.workflow.setStatus(com.jizza.engine.api.enums.WorkflowStatus.PENDING_REVIEW);

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("amount", 1),
                "scheduled_task:abc"))
        .isInstanceOf(com.jizza.engine.api.error.ValidationException.class)
        .hasMessageContaining("not approved");

    assertThat(harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("amount", 1),
        "manual").status()).isEqualTo(RunStatus.DONE);
  }

  @Test
  void aDraftRunsUnattendedFromTheLibraryOnlyForTheStepsTheUserAllowed() {
    EngineHarness harness = linear();
    harness.workflow.setStatus(com.jizza.engine.api.enums.WorkflowStatus.DRAFT);
    var allowed = new com.jizza.engine.api.workflow.library.ReuseProfile("Pay small invoices",
        java.util.List.of(), java.util.List.of(),
        new com.jizza.engine.api.workflow.library.ReuseProfile.Guardrails(true, true, null,
            java.util.List.of(), harness.workflow.getGraphFingerprint()), null);
    harness.workflow.setReuse(allowed.toJson());

    assertThat(harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("amount", 1),
        "library:scheduled_task:abc").status()).isEqualTo(RunStatus.DONE);
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("amount", 1),
                "scheduled_task:abc"))
        .as("the allowance is for library reuse, not any unattended start")
        .hasMessageContaining("not approved");

    harness.workflow.setGraphFingerprint("steps-changed-since");
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("amount", 1),
                "library:scheduled_task:abc"))
        .as("editing the steps lapses the permission")
        .hasMessageContaining("not approved");
  }

  @Test
  void aDisabledWorkflowCannotBeRunAtAll() {
    EngineHarness harness = linear();
    harness.workflow.setDisabled(true);

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("amount", 1), "manual"))
        .isInstanceOf(com.jizza.engine.api.error.ValidationException.class)
        .hasMessageContaining("disabled");
  }

  @Test
  void cancellingStopsTheRun() {
    EngineHarness harness = withIntervention();
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    harness.engine.cancel(run.id());

    assertThat(harness.engine.get(run.id()).status()).isEqualTo(RunStatus.CANCELLED);
  }

  @Test
  void aFailingToolCallIsRetriedThenSurfacedRatherThanSwallowed() {
    EngineHarness harness = sideEffectHarness();
    harness.nextToolResult = ToolResult.error("The service is rate limiting this connection");

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(harness.toolInvocations).isGreaterThan(1);
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
  }
}
