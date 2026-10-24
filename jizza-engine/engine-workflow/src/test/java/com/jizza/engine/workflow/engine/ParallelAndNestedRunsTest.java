package com.jizza.engine.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.RunStepStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Parallel paths, joins, wait steps, sub-workflows and for-each loops on the saga engine. */
class ParallelAndNestedRunsTest {

  private static WorkflowNode node(String id, NodeType type, String configJson) {
    return new WorkflowNode(id, type, id, configJson == null ? null : Json.read(configJson), null);
  }

  private static WorkflowNode once(String id, NodeType type, String configJson) {
    return new WorkflowNode(id, type, id, configJson == null ? null : Json.read(configJson),
        new WorkflowNode.RetryPolicy(1, 1, 1.0, 10));
  }

  private static WorkflowEdge edge(String from, String to) { return new WorkflowEdge(from, to, null); }

  private static String agentStep() {
    return "{\"agentId\":\"" + UUID.randomUUID() + "\",\"instruction\":\"do it\"}";
  }

  // ── parallel paths and joins ─────────────────────────────────────────────

  /** start → split → (a, b) → join → after → finish */
  private static WorkflowGraph diamond(String bType, String bConfig) {
    return new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("split", NodeType.PARALLEL, null),
            node("a", NodeType.TRANSFORM, "{\"expression\":\"'from a'\"}"),
            node("b", NodeType.valueOf(bType), bConfig),
            node("join", NodeType.JOIN, null),
            node("after", NodeType.AGENT_STEP, agentStep()),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "split"), new WorkflowEdge("split", "a", "a"),
            new WorkflowEdge("split", "b", "b"), edge("a", "join"), edge("b", "join"),
            edge("join", "after"), edge("after", "finish")));
  }

  @Test
  void aParallelSplitRunsEveryPathAndTheJoinOnce() {
    EngineHarness harness = new EngineHarness(
        diamond("TRANSFORM", "{\"expression\":\"'from b'\"}"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.agentStepRuns.get("after")).isEqualTo(1);
    var joined = harness.step(run.id(), "join").orElseThrow().getOutput();
    assertThat(joined.path("a").asText()).isEqualTo("from a");
    assertThat(joined.path("b").asText()).isEqualTo("from b");
  }

  @Test
  void aPathWaitingForThePersonDoesNotHoldUpTheOthers() {
    EngineHarness harness = new EngineHarness(
        diamond("HUMAN_INTERVENTION", "{\"prompt\":\"Which account?\"}"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(run.currentNode()).isEqualTo("b");
    assertThat(harness.step(run.id(), "a").orElseThrow().getStatus())
        .as("the other path ran while this one waited").isEqualTo(RunStepStatus.DONE);
    assertThat(harness.step(run.id(), "join")).as("the join waits for both paths").isEmpty();

    var resumed = harness.engine.resume(run.id(), Json.obj().put("instruction", "savings"));

    assertThat(resumed.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.agentStepRuns.get("after")).isEqualTo(1);
    assertThat(harness.step(run.id(), "join").orElseThrow().getOutput()
        .path("b").path("instruction").asText()).isEqualTo("savings");
  }

  @Test
  void twoPathsWaitingAtOnceNeedTheAnswerToSayWhichStep() {
    EngineHarness harness = new EngineHarness(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("split", NodeType.PARALLEL, null),
            node("askA", NodeType.HUMAN_INTERVENTION, "{\"prompt\":\"A?\"}"),
            node("askB", NodeType.HUMAN_INTERVENTION, "{\"prompt\":\"B?\"}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "split"), new WorkflowEdge("split", "askA", "a"),
            new WorkflowEdge("split", "askB", "b"), edge("askA", "finish"),
            edge("askB", "finish"))));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(harness.raised).hasSize(2);
    assertThat(harness.raised).extracting(n -> n.context().path("node").asText())
        .containsExactlyInAnyOrder("askA", "askB");

    assertThatThrownBy(() -> harness.engine.resume(run.id(), Json.obj().put("instruction", "x")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("say which");

    var afterA = harness.engine.resume(run.id(),
        Json.obj().put("instruction", "yes").put("node", "askA"));
    assertThat(afterA.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(afterA.currentNode()).isEqualTo("askB");

    var afterB = harness.engine.resume(run.id(), Json.obj().put("instruction", "no"));
    assertThat(afterB.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "finish").orElseThrow().getOutput().path("askB")
        .path("instruction").asText()).isEqualTo("no");
  }

  @Test
  void aJoinAfterABranchDoesNotWaitForTheSideNotTaken() {
    EngineHarness harness = new EngineHarness(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("split", NodeType.PARALLEL, null),
            node("check", NodeType.BRANCH, "{\"condition\":\"input.big\"}"),
            node("escalate", NodeType.TRANSFORM, "{\"expression\":\"'escalated'\"}"),
            node("other", NodeType.TRANSFORM, "{\"expression\":\"'other path'\"}"),
            node("join", NodeType.JOIN, null),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "split"), new WorkflowEdge("split", "check", "a"),
            new WorkflowEdge("split", "other", "b"),
            new WorkflowEdge("check", "escalate", "true"),
            new WorkflowEdge("check", "join", "false"),
            edge("escalate", "join"), edge("other", "join"), edge("join", "finish"))));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("big", false), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "escalate").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.SKIPPED);
    var joined = harness.step(run.id(), "join").orElseThrow().getOutput();
    assertThat(joined.has("check")).as("the branch's own 'no' path arrived").isTrue();
    assertThat(joined.has("escalate")).isFalse();
    assertThat(joined.path("other").asText()).isEqualTo("other path");
  }

  @Test
  void aRunFromBeforeTheFrontierCarriesOnFromItsCurrentNode() {
    EngineHarness harness = new EngineHarness(new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("big", NodeType.BRANCH, "{\"condition\":\"true\"}"),
            node("yes", NodeType.TRANSFORM, "{\"expression\":\"'yes'\"}"),
            node("no", NodeType.TRANSFORM, "{\"expression\":\"'no'\"}"),
            node("merge", NodeType.AGENT_STEP, agentStep()),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "big"), new WorkflowEdge("big", "yes", "true"),
            new WorkflowEdge("big", "no", "false"), edge("yes", "merge"), edge("no", "merge"),
            edge("merge", "finish"))));

    // A row left by the old engine: on "yes", with no frontier and no record of skipped paths.
    WorkflowRunEntity old = new WorkflowRunEntity();
    old.setUserId(harness.userId);
    old.setWorkflowId(harness.workflow.getId());
    old.setVersion(1);
    old.setTriggerSource("manual");
    old.setStatus(RunStatus.RUNNING);
    old.setCurrentNode("yes");
    old.setContext(RunContext.start(Json.obj()).toJson());
    harness.runs.save(old);

    var resumed = harness.engine.resumeAfterRestart(old.getId());

    assertThat(resumed.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.agentStepRuns.get("merge")).isEqualTo(1);
  }

  // ── wait steps ───────────────────────────────────────────────────────────

  private static WorkflowGraph waitThen(String waitConfig) {
    return new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("pause", NodeType.WAIT, waitConfig),
            node("after", NodeType.AGENT_STEP, agentStep()),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "pause"), edge("pause", "after"), edge("after", "finish")));
  }

  @Test
  void aWaitStepParksTheRunUntilItsTimeComes() {
    EngineHarness harness = new EngineHarness(waitThen("{\"duration\":\"PT2H\"}"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.WAITING);
    assertThat(run.currentNode()).isEqualTo("pause");
    assertThat(harness.raised).as("waiting asks nobody anything").isEmpty();

    harness.clock.advance(Duration.ofMinutes(90));
    assertThat(harness.engine.wake(run.id()).status()).isEqualTo(RunStatus.WAITING);
    assertThat(harness.agentStepRuns).doesNotContainKey("after");

    harness.clock.advance(Duration.ofMinutes(31));
    var woken = harness.engine.wake(run.id());

    assertThat(woken.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "pause").orElseThrow().getOutput().path("waitedUntil")
        .asText()).isEqualTo("2026-10-21T10:00:00Z");
  }

  @Test
  void aWaitForATimeAlreadyPassedDoesNotWait() {
    EngineHarness harness = new EngineHarness(waitThen("{\"until\":\"2026-10-21T13:00:00+05:30\"}"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).as("13:00 IST is 07:30 UTC, before the clock's 08:00")
        .isEqualTo(RunStatus.DONE);
  }

  @Test
  void aTimeWithoutAnOffsetIsReadInIndianTime() {
    EngineHarness harness = new EngineHarness(waitThen("{\"until\":\"2026-10-21T15:30:00\"}"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.WAITING);
    assertThat(RunContext.of(harness.run(run.id()).getContext()).wakeAt("pause"))
        .hasToString("2026-10-21T10:00:00Z");
  }

  // ── sub-workflows ────────────────────────────────────────────────────────

  private static WorkflowGraph doubler() {
    return new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("double", NodeType.TRANSFORM, "{\"expression\":\"input.item * 2\"}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "double"), edge("double", "finish")));
  }

  private static WorkflowGraph callerOf(UUID child, String inputExpr) {
    return new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("call", NodeType.SUB_WORKFLOW, "{\"workflowId\":\"" + child + "\""
                + (inputExpr == null ? "" : ",\"inputExpr\":\"" + inputExpr + "\"") + "}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "call"), edge("call", "finish")));
  }

  private static void setGraph(WorkflowEntity workflow, WorkflowGraph graph) {
    workflow.setGraph(graph.toJson());
    workflow.setGraphFingerprint(graph.fingerprint());
  }

  @Test
  void aSubWorkflowStepCompletesWithWhatTheChildProduced() {
    EngineHarness harness = new EngineHarness(doubler());
    WorkflowEntity child = harness.addWorkflow("Double it", doubler());
    setGraph(harness.workflow, callerOf(child.getId(), "{'item': input.amount}"));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("amount", 21), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "call").orElseThrow().getOutput().asInt()).isEqualTo(42);
    assertThat(RunContext.of(harness.run(run.id()).getContext()).result().asInt())
        .as("the parent's own result is its end step's input").isEqualTo(42);
  }

  /** A module: declares an amount it needs and the result it gives. */
  private static WorkflowGraph doublingModule() {
    return new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, "{\"inputs\":[{\"name\":\"amount\",\"type\":\"number\","
                + "\"required\":true},{\"name\":\"label\",\"type\":\"text\",\"default\":\"total\"}]}"),
            node("finish", NodeType.END, "{\"outputs\":[{\"name\":\"doubled\",\"type\":\"number\","
                + "\"value\":\"{{ input.amount * 2 }}\"},{\"name\":\"summary\",\"type\":\"text\","
                + "\"value\":\"{{ input.label }} is {{ input.amount * 2 }}\"}]}")),
        List.of(edge("start", "finish")));
  }

  private static WorkflowGraph moduleCaller(UUID child, String inputsJson) {
    return new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("call", NodeType.SUB_WORKFLOW, "{\"workflowId\":\"" + child + "\",\"inputs\":" + inputsJson + "}"),
            node("after", NodeType.TRANSFORM, "{\"expression\":\"nodes.call.doubled + 1\"}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "call"), edge("call", "after"), edge("after", "finish")));
  }

  @Test
  void aModuleTakesItsMappedInputsAndLaterStepsMapItsOutputs() {
    EngineHarness harness = new EngineHarness(doubler());
    WorkflowEntity module = harness.addWorkflow("Double it", doublingModule());
    // The amount arrives as text and the module declares a number, so it is read as one.
    setGraph(harness.workflow, moduleCaller(module.getId(), "{\"amount\":\"{{ input.total }}\",\"label\":\"\"}"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("total", "21"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    var call = harness.step(run.id(), "call").orElseThrow().getOutput();
    assertThat(call.path("doubled").asInt()).isEqualTo(42);
    assertThat(call.path("summary").asText()).as("a blank mapping leaves the default").isEqualTo("total is 42");
    assertThat(harness.step(run.id(), "after").orElseThrow().getOutput().asInt()).isEqualTo(43);
  }

  @Test
  void aModuleStepFailsByNameWhenARequiredInputIsMissing() {
    EngineHarness harness = new EngineHarness(doubler());
    WorkflowEntity module = harness.addWorkflow("Double it", doublingModule());
    setGraph(harness.workflow, moduleCaller(module.getId(), "{\"amount\":\"{{ input.nothing }}\"}"));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isNotEqualTo(RunStatus.DONE);
    assertThat(harness.step(run.id(), "call").orElseThrow().getError()).contains("needs the input amount");
  }

  @Test
  void aRunOfAModuleIsCheckedAgainstItsInputs() {
    EngineHarness harness = new EngineHarness(doublingModule());
    assertThatThrownBy(() -> harness.engine.start(harness.workflow.getId(), 1,
        Json.obj().put("amount", "lots"), "manual"))
        .hasMessageContaining("'amount' should be a number");
    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj().put("amount", 5), "manual");
    assertThat(RunContext.of(harness.run(run.id()).getContext()).result().path("doubled").asInt()).isEqualTo(10);
  }

  @Test
  void aParentWaitsForAChildThatAsksThePersonAndCarriesOnWhenItFinishes() {
    EngineHarness harness = new EngineHarness(doubler());
    WorkflowEntity child = harness.addWorkflow("Confirm", new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("ask", NodeType.HUMAN_INTERVENTION, "{\"prompt\":\"Go ahead?\"}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "ask"), edge("ask", "finish"))));
    setGraph(harness.workflow, callerOf(child.getId(), null));

    var parent = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");
    assertThat(parent.status()).isEqualTo(RunStatus.WAITING);
    WorkflowRunEntity childRun = harness.runRows.stream()
        .filter(r -> r.getWorkflowId().equals(child.getId())).findFirst().orElseThrow();
    assertThat(childRun.getStatus()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(childRun.getTriggerSource()).isEqualTo("manual:subworkflow:" + parent.id());

    harness.engine.resume(childRun.getId(), Json.obj().put("instruction", "go"));

    assertThat(harness.run(parent.id()).getStatus())
        .as("the child finishing woke the parent").isEqualTo(RunStatus.DONE);
    assertThat(harness.step(parent.id(), "call").orElseThrow().getOutput().path("instruction")
        .asText()).isEqualTo("go");
  }

  @Test
  void aWorkflowCannotRunItselfAsAStep() {
    EngineHarness harness = new EngineHarness(doubler());
    setGraph(harness.workflow, callerOf(harness.workflow.getId(), null));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.raised.get(0).body()).contains("cannot run itself");
    assertThat(harness.runRows).as("no child run was created").hasSize(1);
  }

  @Test
  void anotherPersonsWorkflowCannotBeRunAsAStep() {
    EngineHarness harness = new EngineHarness(doubler());
    WorkflowEntity theirs = harness.addWorkflow("Not mine", doubler());
    theirs.setUserId(UUID.randomUUID());
    setGraph(harness.workflow, callerOf(theirs.getId(), null));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.runRows).hasSize(1);
  }

  @Test
  void anUnattendedRunCannotStartADraftChild() {
    EngineHarness harness = new EngineHarness(doubler());
    WorkflowEntity draft = harness.addWorkflow("Draft", doubler());
    draft.setStatus(com.jizza.engine.api.enums.WorkflowStatus.DRAFT);
    setGraph(harness.workflow, callerOf(draft.getId(), null));

    var run = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "schedule");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.raised.get(0).body()).contains("not approved");
  }

  @Test
  void cancellingAParentCancelsTheChildItIsWaitingOn() {
    EngineHarness harness = new EngineHarness(doubler());
    WorkflowEntity child = harness.addWorkflow("Slow", waitThen("{\"duration\":\"P1D\"}"));
    setGraph(harness.workflow, callerOf(child.getId(), null));

    var parent = harness.engine.start(harness.workflow.getId(), 1, Json.obj(), "manual");
    harness.engine.cancel(parent.id());

    assertThat(harness.runRows).filteredOn(r -> r.getWorkflowId().equals(child.getId()))
        .singleElement().extracting(WorkflowRunEntity::getStatus).isEqualTo(RunStatus.CANCELLED);
  }

  // ── for-each ─────────────────────────────────────────────────────────────

  private static WorkflowGraph forEach(UUID child, String extra) {
    return new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("each", NodeType.FOR_EACH, "{\"workflowId\":\"" + child
                + "\",\"items\":\"input.amounts\"" + extra + "}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "each"), edge("each", "finish")));
  }

  @Test
  void forEachRunsTheWorkflowOncePerItemAndKeepsTheOrder() {
    EngineHarness harness = new EngineHarness(doubler());
    WorkflowEntity child = harness.addWorkflow("Double it", doubler());
    setGraph(harness.workflow, forEach(child.getId(), ",\"concurrency\":3"));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.read("{\"amounts\":[1,2,3]}"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    var output = harness.step(run.id(), "each").orElseThrow().getOutput();
    assertThat(output.path("results").toString()).isEqualTo("[2,4,6]");
    assertThat(output.path("count").asInt()).isEqualTo(3);
    assertThat(harness.runRows).hasSize(4);
  }

  @Test
  void forEachOneAtATimeStartsTheNextItemWhenTheLastFinishes() {
    EngineHarness harness = new EngineHarness(doubler());
    WorkflowEntity child = harness.addWorkflow("Slow", waitThen("{\"duration\":\"PT1H\"}"));
    setGraph(harness.workflow, forEach(child.getId(), ""));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.read("{\"amounts\":[1,2]}"), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.WAITING);
    assertThat(harness.runRows).as("only the first item has started").hasSize(2);

    harness.clock.advance(Duration.ofHours(1));
    harness.runRows.stream().filter(r -> r.getWorkflowId().equals(child.getId())).toList()
        .forEach(r -> harness.engine.wake(r.getId()));
    assertThat(harness.runRows).as("the first finishing started the second").hasSize(3);

    harness.clock.advance(Duration.ofHours(1));
    harness.runRows.stream().filter(r -> r.getWorkflowId().equals(child.getId())).toList()
        .forEach(r -> harness.engine.wake(r.getId()));
    assertThat(harness.run(run.id()).getStatus()).isEqualTo(RunStatus.DONE);
  }

  @Test
  void aFailedItemStopsTheLoopAndAnsweringRunsOnlyTheFailedItemsAgain() {
    EngineHarness harness = new EngineHarness(doubler());
    AtomicInteger calls = new AtomicInteger();
    harness.agentStepBehaviour = nodeId -> calls.incrementAndGet() == 2
        ? NodeExecutor.Outcome.permanentFailure("the bank said no")
        : NodeExecutor.Outcome.completed(Json.obj().put("call", calls.get()));
    WorkflowEntity child = harness.addWorkflow("Pay", new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            once("pay", NodeType.AGENT_STEP, "{\"agentId\":\"" + UUID.randomUUID()
                + "\",\"instruction\":\"pay\",\"onFailure\":\"dead_letter\"}"),
            node("finish", NodeType.END, null)),
        List.of(edge("start", "pay"), edge("pay", "finish"))));
    setGraph(harness.workflow, forEach(child.getId(), ""));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.read("{\"amounts\":[10,20,30]}"), "manual");
    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.raised.get(harness.raised.size() - 1).body())
        .contains("Item 1").contains("the bank said no");

    var resumed = harness.engine.resume(run.id(), Json.obj().put("instruction", "try again"));

    assertThat(resumed.status()).isEqualTo(RunStatus.DONE);
    assertThat(calls.get()).as("item 0 was not paid twice").isEqualTo(4);
  }

  @Test
  void continueOnErrorListsFailedItemsAndFinishesTheRest() {
    EngineHarness harness = new EngineHarness(doubler());
    AtomicInteger calls = new AtomicInteger();
    harness.agentStepBehaviour = nodeId -> calls.incrementAndGet() == 1
        ? NodeExecutor.Outcome.permanentFailure("no")
        : NodeExecutor.Outcome.completed(Json.obj().put("ok", true));
    WorkflowEntity child = harness.addWorkflow("Try", new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            once("try", NodeType.AGENT_STEP, "{\"agentId\":\"" + UUID.randomUUID()
                + "\",\"instruction\":\"try\",\"onFailure\":\"dead_letter\"}")),
        List.of(edge("start", "try"))));
    setGraph(harness.workflow, forEach(child.getId(), ",\"continueOnError\":true"));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.read("{\"amounts\":[1,2]}"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.DONE);
    var output = harness.step(run.id(), "each").orElseThrow().getOutput();
    assertThat(output.path("failed").get(0).path("index").asInt()).isZero();
    assertThat(output.path("results").get(0).isNull()).isTrue();
    assertThat(output.path("results").get(1).path("ok").asBoolean()).isTrue();
  }

  @Test
  void forEachRefusesMoreItemsThanItsLimit() {
    EngineHarness harness = new EngineHarness(doubler());
    WorkflowEntity child = harness.addWorkflow("Double it", doubler());
    setGraph(harness.workflow, forEach(child.getId(), ",\"maxItems\":2"));

    var run = harness.engine.start(harness.workflow.getId(), 1,
        Json.read("{\"amounts\":[1,2,3]}"), "manual");

    assertThat(run.status()).isEqualTo(RunStatus.PAUSED_WAITING_INSTRUCTION);
    assertThat(harness.raised.get(0).body()).contains("limit of 2");
    assertThat(harness.runRows).hasSize(1);
  }

  // ── validation ───────────────────────────────────────────────────────────

  @Test
  void theValidatorChecksTheNewSteps() {
    WorkflowValidator validator = new WorkflowValidator();
    WorkflowNode start = node("start", NodeType.TRIGGER, null);

    assertThatThrownBy(() -> validator.validate(new WorkflowGraph(
        List.of(start, node("split", NodeType.PARALLEL, null), node("a", NodeType.END, null)),
        List.of(edge("start", "split"), edge("split", "a")))))
        .hasMessageContaining("at least two paths");

    assertThatThrownBy(() -> validator.validate(new WorkflowGraph(
        List.of(start, node("pause", NodeType.WAIT, "{\"duration\":\"two hours\"}")),
        List.of(edge("start", "pause")))))
        .hasMessageContaining("ISO-8601");

    assertThatThrownBy(() -> validator.validate(new WorkflowGraph(
        List.of(start, node("each", NodeType.FOR_EACH,
            "{\"workflowId\":\"" + UUID.randomUUID() + "\"}")),
        List.of(edge("start", "each")))))
        .hasMessageContaining("'items'");

    assertThatThrownBy(() -> validator.validate(new WorkflowGraph(
        List.of(start, node("a", NodeType.TRANSFORM, "{\"expression\":\"1\"}"),
            node("b", NodeType.TRANSFORM, "{\"expression\":\"2\"}")),
        List.of(edge("start", "a"), edge("start", "b")))))
        .hasMessageContaining("may fork");
  }
}
