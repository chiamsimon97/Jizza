package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.agent.CapabilityRegistry;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.TaskStatus;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.repository.ScheduledTaskRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import com.jizza.engine.scheduler.config.SchedulerProperties;
import com.jizza.engine.scheduler.run.TaskDispatcher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance: a repeated job becomes a reviewed procedure that then runs exactly as approved, and
 * the autonomy controls actually stop things happening (build prompt Acceptance Criteria 11 and 12).
 *
 * <p>The two halves belong together because they are the same promise from two directions: nothing
 * an agent writes runs until a human has looked at it, and nothing an agent does unattended escapes
 * the policy the user set.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class ProceduresAndAutonomyE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private AgentDirectory agents;
  @Autowired private CapabilityRegistry capabilities;
  @Autowired private AutonomyGate autonomyGate;
  @Autowired private TaskDispatcher dispatcher;
  @Autowired private SchedulerProperties schedulerProps;
  @Autowired private ScheduledTaskRepository tasks;
  @Autowired private WorkflowRepository workflows;
  @Autowired private WorkflowRunRepository runs;
  @Autowired private WorkflowEngine engine;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient api;
  private UUID userId;
  private String email;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();

    email = "proc-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    userId = UUID.fromString(api.post("/api/auth/login",
        Map.of("email", email, "password", PASSWORD)).json().path("userId").asText());
    api.post("/api/auth/step-up", Map.of("password", PASSWORD));
    schedulerProps.setEnabled(true);
    parkWorkLeftDueByOtherTests();
  }

  /**
   * A dispatch pass polls every user's tasks, so anything another test left due would run inside
   * this one and show up in its assertions. Park it a year out; each test asserts only about the
   * work it created itself.
   */
  private void parkWorkLeftDueByOtherTests() {
    for (var stale : tasks.findDue(TaskStatus.PENDING, Instant.now())) {
      stale.setNextRunAt(Instant.now().plus(java.time.Duration.ofDays(365)));
      tasks.save(stale);
    }
  }

  private UUID agentId(String key) {
    return agents.byKey(userId, key).map(AgentDto::id).orElseThrow();
  }

  /** Polls for something a background listener produces, so the test does not race it. */
  private <T> T eventually(Supplier<T> attempt, Predicate<T> until) {
    T last = null;
    for (int i = 0; i < 100; i++) {
      last = attempt.get();
      if (until.test(last)) return last;
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return last;
  }

  private WorkflowNode node(String id, NodeType type, JsonNode config) {
    return new WorkflowNode(id, type, id, config, null);
  }

  /** A two-step procedure whose transform output makes it obvious which version ran. */
  private WorkflowGraph procedureGraph(String marker) {
    return new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("step", NodeType.TRANSFORM, Json.obj().put("expression", "'" + marker + "'")),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "step", null),
            new WorkflowEdge("step", "finish", null)));
  }

  /** Creates a recurring job the user has marked repetitive, wound so it is due now. */
  private UUID repetitiveTask(String title) {
    var created = api.post("/api/tasks", Map.of(
        "title", title, "agentId", agentId("professional").toString(),
        "payload", Json.obj().put("instruction", "do the weekly filing"),
        "scheduleType", "RECURRING", "cronOrRrule", "0 0 9 * * MON",
        "timezone", "Asia/Kolkata", "repetitive", true));
    assertThat(created.ok()).as(created.body()).isTrue();
    return windDue(UUID.fromString(created.json().path("id").asText()));
  }

  /** One dispatcher tick, with the inter-tick lock hold cleared first. */
  private void dispatch() {
    releaseDispatchLock();
    dispatcher.dispatchDue();
  }

  private UUID windDue(UUID taskId) {
    var task = tasks.findById(taskId).orElseThrow();
    task.setNextRunAt(Instant.now().minusSeconds(1));
    tasks.save(task);
    return taskId;
  }

  /** The agent that did the work submits a draft, exactly as it would from its own turn. */
  private UUID propose(UUID taskId, String name, WorkflowGraph graph) {
    var args = Json.obj()
        .put("name", name)
        .put("description", "What I do every week");
    if (taskId != null) args.put("source_task_id", taskId.toString());
    args.set("graph", graph.toJson());

    var result = capabilities.invoke(agentId("professional"),
        new ToolCall("p1", "propose_workflow", args));
    assertThat(result.ok()).as(String.valueOf(result.error())).isTrue();
    assertThat(result.output().path("status").asText()).isEqualTo("pending_review");
    return UUID.fromString(result.output().path("workflow_id").asText());
  }

  /** The newest unread notification of a kind -- the list is newest first, so it is the one the
   * step just raised. */
  private JsonNode notificationOfKind(NotificationKind kind) {
    var pending = api.get("/api/notifications?unreadOnly=true");
    return java.util.stream.StreamSupport.stream(pending.json().spliterator(), false)
        .filter(n -> n.path("kind").asText().equals(kind.name()))
        .findFirst().orElseThrow(() ->
            new AssertionError("no " + kind + " notification in " + pending.body()));
  }

  // ── learning a procedure ─────────────────────────────────────────────────

  @Test
  void aRepetitiveJobPromptsTheAgentThatRanItToProposeAProcedureForReview() {
    UUID taskId = repetitiveTask("Weekly filing");
    // Cato's turns, in order: it does the job, then the learner asks it to write down what it did
    // and it submits a single draft.
    model.scriptFor("Cato",
        LlmResponse.text("Filed everything."),
        new LlmResponse("", List.of(new ToolCall("t1", "propose_workflow", Json.obj()
            .put("name", "Weekly filing")
            .put("description", "How the weekly filing gets done")
            .put("source_task_id", taskId.toString())
            .set("graph", procedureGraph("filed").toJson()))), "tool_use", 0, 0, "stub"),
        LlmResponse.text("Submitted for your review."));

    dispatch();

    var proposed = eventually(() -> workflows.findBySourceTaskId(taskId), found -> !found.isEmpty());
    assertThat(proposed).as("the agent wrote the job down").isNotEmpty();
    var workflow = proposed.get(0);
    assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.PENDING_REVIEW);
    assertThat(workflow.getVersion()).isEqualTo(1);

    // Proposing changes nothing: the job stays free-form until the user approves.
    assertThat(tasks.findById(taskId).orElseThrow().getBoundWorkflowId()).isNull();

    var review = notificationOfKind(NotificationKind.WORKFLOW_REVIEW);
    assertThat(review.path("sourceAgentName").asText()).isEqualTo("Cato");
    assertThat(review.path("body").asText()).contains("needs your review");
  }

  @Test
  void anAgentIsAskedToWriteUpAJobOnlyOnce() {
    UUID taskId = repetitiveTask("Weekly filing");
    propose(taskId, "Weekly filing", procedureGraph("v1"));
    model.scriptFor("Cato", LlmResponse.text("Filed everything."));

    dispatch();

    // A draft already exists for this job, so the learner leaves the agent alone; the only turn is
    // the job itself.
    assertThat(model.requests).hasSize(1);
    assertThat(workflows.findBySourceTaskId(taskId)).hasSize(1);
  }

  // ── approve, bind, and run exactly ───────────────────────────────────────

  @Test
  void approvingBindsTheProcedureToTheJobItCameFrom() {
    UUID taskId = repetitiveTask("Weekly filing");
    UUID workflowId = propose(taskId, "Weekly filing", procedureGraph("v1"));

    var approved = api.post("/api/procedures/" + workflowId + "/approve", null);

    assertThat(approved.ok()).as(approved.body()).isTrue();
    var task = api.get("/api/tasks/" + taskId).json();
    assertThat(UUID.fromString(task.path("boundWorkflowId").asText())).isEqualTo(workflowId);
    assertThat(task.path("boundVersion").asInt()).isEqualTo(1);
  }

  @Test
  void aBoundJobRunsTheApprovedGraphRatherThanImprovising() {
    UUID taskId = repetitiveTask("Weekly filing");
    UUID workflowId = propose(taskId, "Weekly filing", procedureGraph("ran-the-procedure"));
    api.post("/api/procedures/" + workflowId + "/approve", null);
    windDue(taskId);

    dispatch();

    var run = runs.findAll().stream().filter(r -> workflowId.equals(r.getWorkflowId()))
        .findFirst().orElseThrow();
    assertThat(run.getVersion()).isEqualTo(1);
    assertThat(run.getTriggerSource()).isEqualTo("scheduled_task:" + taskId);
    assertThat(tasks.findById(taskId).orElseThrow().getLastResult())
        .contains("Ran procedure version 1");
    assertThat(model.requests).as("no free-form model turn: the graph is the instruction").isEmpty();
  }

  @Test
  void aBoundJobRefusesToRunAndAsksForInstructionsWhenTheGraphNoLongerMatchesWhatWasApproved() {
    UUID taskId = repetitiveTask("Weekly filing");
    UUID workflowId = propose(taskId, "Weekly filing", procedureGraph("v1"));
    api.post("/api/procedures/" + workflowId + "/approve", null);

    // The live graph is edited out from under the binding, without going through review.
    var tampered = workflows.findById(workflowId).orElseThrow();
    tampered.setGraph(procedureGraph("tampered").toJson());
    workflows.save(tampered);
    windDue(taskId);

    dispatch();

    assertThat(runs.findAll()).as("nothing ran")
        .noneMatch(r -> workflowId.equals(r.getWorkflowId()));
    var task = tasks.findById(taskId).orElseThrow();
    assertThat(task.getLastResult()).startsWith("Failed");
    assertThat(task.getStatus()).as("a recurring job stays queued for its next occurrence")
        .isEqualTo(TaskStatus.PENDING);

    var intervention = notificationOfKind(NotificationKind.INSTRUCTION_REQUIRED);
    assertThat(intervention.path("body").asText())
        .contains("does not match the version that was approved");
    assertThat(api.get("/api/activity?action=procedure.binding_violation").json()).isNotEmpty();
  }

  @Test
  void aBoundJobRefusesToRunOnceTheProcedureIsNoLongerApproved() {
    UUID taskId = repetitiveTask("Weekly filing");
    UUID workflowId = propose(taskId, "Weekly filing", procedureGraph("v1"));
    api.post("/api/procedures/" + workflowId + "/approve", null);

    var reverted = workflows.findById(workflowId).orElseThrow();
    reverted.setStatus(WorkflowStatus.PENDING_REVIEW);
    workflows.save(reverted);
    windDue(taskId);

    dispatch();

    assertThat(runs.findAll()).noneMatch(r -> workflowId.equals(r.getWorkflowId()));
    assertThat(notificationOfKind(NotificationKind.INSTRUCTION_REQUIRED).path("body").asText())
        .contains("rather than approved");
  }

  // ── editing, rollback and disabling ──────────────────────────────────────

  @Test
  void editingAnApprovedProcedureUnbindsItAndSendsItBackForReview() {
    UUID taskId = repetitiveTask("Weekly filing");
    UUID workflowId = propose(taskId, "Weekly filing", procedureGraph("v1"));
    api.post("/api/procedures/" + workflowId + "/approve", null);

    var revised = api.post("/api/procedures/" + workflowId + "/revise",
        Map.of("graph", procedureGraph("v2").toJson()));

    assertThat(revised.ok()).as(revised.body()).isTrue();
    assertThat(revised.json().path("version").asInt()).isEqualTo(2);
    assertThat(revised.json().path("status").asText()).isEqualTo("PENDING_REVIEW");
    assertThat(api.get("/api/tasks/" + taskId).json().path("boundWorkflowId").isNull())
        .as("an unreviewed edit cannot stay bound to a job").isTrue();
    assertThat(notificationOfKind(NotificationKind.WORKFLOW_REVIEW).path("body").asText())
        .contains("needs reviewing again");
  }

  @Test
  void theHistoryAndDiffShowWhatChangedBetweenVersions() {
    UUID workflowId = propose(null, "Weekly filing", procedureGraph("v1"));
    api.post("/api/procedures/" + workflowId + "/approve", null);
    api.post("/api/procedures/" + workflowId + "/revise",
        Map.of("graph", procedureGraph("v2").toJson()));

    assertThat(api.get("/api/procedures/" + workflowId + "/history").json()).hasSize(2);

    var diff = api.get("/api/procedures/" + workflowId + "/diff?from=1&to=2");
    assertThat(diff.ok()).as(diff.body()).isTrue();
    assertThat(diff.json().path("changedNodes").toString()).contains("step");
    assertThat(diff.json().path("addedNodes")).isEmpty();
    assertThat(diff.json().path("removedNodes")).isEmpty();
  }

  @Test
  void rollingBackRestoresAnApprovedVersionAndRebindsTheJobToIt() {
    UUID taskId = repetitiveTask("Weekly filing");
    UUID workflowId = propose(taskId, "Weekly filing", procedureGraph("v1"));
    api.post("/api/procedures/" + workflowId + "/approve", null);
    api.post("/api/procedures/" + workflowId + "/revise",
        Map.of("graph", procedureGraph("v2").toJson()));
    api.post("/api/procedures/" + workflowId + "/approve", null);

    var rolled = api.post("/api/procedures/" + workflowId + "/rollback?toVersion=1", null);

    assertThat(rolled.ok()).as(rolled.body()).isTrue();
    var workflow = workflows.findById(workflowId).orElseThrow();
    assertThat(workflow.getVersion()).as("a rollback is a new version, not a rewrite").isEqualTo(3);
    assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.APPROVED);
    assertThat(workflow.getGraphFingerprint()).isEqualTo(procedureGraph("v1").fingerprint());
    assertThat(api.get("/api/tasks/" + taskId).json().path("boundVersion").asInt()).isEqualTo(3);

    // And the rebound job runs the restored graph, not the one that was rolled back.
    windDue(taskId);
    dispatch();
    assertThat(tasks.findById(taskId).orElseThrow().getLastResult())
        .contains("Ran procedure version 3");
  }

  @Test
  void rollingBackToAVersionNobodyApprovedIsRefused() {
    UUID workflowId = propose(null, "Weekly filing", procedureGraph("v1"));
    api.post("/api/procedures/" + workflowId + "/revise",
        Map.of("graph", procedureGraph("v2").toJson()));
    api.post("/api/procedures/" + workflowId + "/approve", null);

    var refused = api.post("/api/procedures/" + workflowId + "/rollback?toVersion=1", null);

    assertThat(refused.status()).isEqualTo(400);
    assertThat(refused.body()).contains("never approved");
  }

  @Test
  void disablingAProcedureUnbindsItSoTheJobStopsFollowingIt() {
    UUID taskId = repetitiveTask("Weekly filing");
    UUID workflowId = propose(taskId, "Weekly filing", procedureGraph("v1"));
    api.post("/api/procedures/" + workflowId + "/approve", null);

    assertThat(api.post("/api/procedures/" + workflowId + "/disable", null).ok()).isTrue();

    assertThat(api.get("/api/tasks/" + taskId).json().path("boundWorkflowId").isNull()).isTrue();
    assertThat(workflows.findById(workflowId).orElseThrow().isDisabled()).isTrue();
  }

  @Test
  void approvingAProcedureNeedsAFreshReauthentication() {
    UUID workflowId = propose(null, "Weekly filing", procedureGraph("v1"));

    // The same user, signed in but never stepped up: the route that makes a graph runnable refuses.
    var stale = new ApiClient(port);
    stale.prime();
    stale.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));

    var refused = stale.post("/api/procedures/" + workflowId + "/approve", null);

    assertThat(refused.status()).isEqualTo(403);
    assertThat(refused.problemType()).endsWith("step-up-required");
    assertThat(workflows.findById(workflowId).orElseThrow().getStatus())
        .isEqualTo(WorkflowStatus.PENDING_REVIEW);
  }

  // ── autonomy: the kill switch ────────────────────────────────────────────

  @Test
  void theKillSwitchStopsScheduledWorkAndReleasingItLetsTheJobRunAgain() {
    model.scriptFor("Cato", LlmResponse.text("Filed."));
    var created = api.post("/api/tasks", Map.of(
        "title", "Halt me", "agentId", agentId("professional").toString(),
        "payload", Json.obj().put("instruction", "file it"),
        "scheduleType", "ONE_TIME", "runAt", Instant.now().minusSeconds(1).toString()));
    UUID taskId = UUID.fromString(created.json().path("id").asText());

    assertThat(api.post("/api/autonomy/kill-switch", Map.of("paused", true)).ok()).isTrue();
    dispatch();

    var halted = tasks.findById(taskId).orElseThrow();
    assertThat(halted.getStatus()).as("still queued, not failed").isEqualTo(TaskStatus.PENDING);
    assertThat(halted.getLastResult()).contains("Skipped").contains("Autonomy is paused");
    assertThat(model.requests).as("the agent was never asked to do anything").isEmpty();

    api.post("/api/autonomy/kill-switch", Map.of("paused", false));
    dispatch();

    var resumed = tasks.findById(taskId).orElseThrow();
    assertThat(resumed.getStatus()).isEqualTo(TaskStatus.DONE);
    assertThat(resumed.getLastResult()).isEqualTo("Filed.");
  }

  @Test
  void theKillSwitchHaltsARunningWorkflowAtItsNextCheckpointAndItPicksUpWhereItStopped() {
    // The run pauses for a decision. The switch is thrown while it waits, so answering must not
    // carry it past the checkpoint until autonomy is back.
    WorkflowGraph graph = new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("ask", NodeType.HUMAN_INTERVENTION, Json.obj().put("prompt", "Proceed?")),
            node("after", NodeType.TRANSFORM, Json.obj().put("expression", "'carried on'")),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "ask", null),
            new WorkflowEdge("ask", "after", null),
            new WorkflowEdge("after", "finish", null)));

    var saved = api.post("/api/workflows", Map.of("name", "Halting", "graph", graph.toJson()));
    UUID workflowId = UUID.fromString(saved.json().path("id").asText());
    var started = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    UUID runId = UUID.fromString(started.json().path("id").asText());
    assertThat(started.json().path("status").asText()).isEqualTo("PAUSED_WAITING_INSTRUCTION");

    api.post("/api/autonomy/kill-switch", Map.of("paused", true));
    var intervention = notificationOfKind(NotificationKind.INSTRUCTION_REQUIRED);
    api.post("/api/notifications/" + intervention.path("id").asText() + "/answer",
        Map.of("kind", "SUBMIT_INSTRUCTION", "instruction", "yes"));

    var halted = api.get("/api/runs/" + runId).json();
    assertThat(halted.path("status").asText())
        .as("halted at the checkpoint rather than carrying on").isEqualTo("RUNNING");
    assertThat(halted.path("currentNode").asText())
        .as("the switch is read between nodes, so the run stops before the answered node runs")
        .isEqualTo("ask");
    assertThat(runs.findById(runId).orElseThrow().getContext().path("instructions").path("ask")
        .asText()).as("the answer is kept for when it does resume").isEqualTo("yes");

    // Releasing the switch and letting the recovery sweep run is all it takes.
    api.post("/api/autonomy/kill-switch", Map.of("paused", false));
    assertThat(engine.resumeAfterRestart(runId).status().name()).isEqualTo("DONE");
    assertThat(api.get("/api/activity?action=run.halted_by_kill_switch").json()).isNotEmpty();
  }

  // ── autonomy: policy ─────────────────────────────────────────────────────

  @Test
  void aDenyRuleBlocksTheToolOutrightAndTheAgentIsToldWhy() {
    UUID cato = agentId("professional");
    var rule = api.put("/api/autonomy/policies", Map.of(
        "agentId", cato.toString(), "toolRef", "schedule_task", "rule", "DENY", "precedence", 50));
    assertThat(rule.ok()).as(rule.body()).isTrue();

    var result = capabilities.invoke(cato, new ToolCall("c1", "schedule_task", Json.obj()
        .put("title", "Nope").put("instruction", "x")
        .put("run_at", Instant.now().plusSeconds(3600).toString())), true);

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("never").contains("schedule_task");
    assertThat(api.get("/api/tasks").json()).as("nothing was scheduled").isEmpty();
    assertThat(api.get("/api/activity?action=tool.blocked").json()).isNotEmpty();
  }

  @Test
  void anAskRuleRoutesTheCallToAPermissionRequestInsteadOfRunningIt() {
    UUID cato = agentId("professional");
    api.put("/api/autonomy/policies", Map.of(
        "agentId", cato.toString(), "toolRef", "schedule_task", "rule", "ASK", "precedence", 50));

    var result = capabilities.invoke(cato, new ToolCall("c1", "schedule_task", Json.obj()
        .put("title", "Ask first").put("instruction", "x")
        .put("run_at", Instant.now().plusSeconds(3600).toString())), true);

    assertThat(result.ok()).as("the call did not execute").isFalse();
    assertThat(result.error()).startsWith("permission_required:");
    assertThat(api.get("/api/tasks").json()).isEmpty();

    var request = notificationOfKind(NotificationKind.PERMISSION_REQUEST);
    assertThat(request.path("sourceAgentName").asText()).isEqualTo("Cato");
    assertThat(request.path("body").asText()).contains("needs approval to run schedule_task");
    assertThat(request.path("expiresAt").isNull())
        .as("a blocking item carries an expiry so it cannot hang forever").isFalse();
  }

  @Test
  void aValueCapSavedThroughTheApiRoutesAnOverLimitCallToApproval() {
    UUID cato = agentId("professional");
    // The account-wide "money: never" default would otherwise catch the over-limit call first.
    for (JsonNode policy : api.get("/api/autonomy/policies").json().path("policies")) {
      if (ActionType.MONEY.token().equals(policy.path("toolRef").asText())) {
        api.delete("/api/autonomy/policies/" + policy.path("id").asText());
      }
    }
    var saved = api.put("/api/autonomy/policies", Map.of(
        "agentId", cato.toString(), "toolRef", "*", "rule", "ALLOW", "precedence", 50,
        "limits", Json.obj().put("field", "body.amount").put("maxValue", 100)));
    assertThat(saved.ok()).as(saved.body()).isTrue();

    // Evaluated at the gate, which is the point every tool call and every autonomous run goes
    // through; the cap reaches into the arguments the tool would have been given.
    assertThat(autonomyGate.evaluate(cato, "pay_supplier",
        Json.obj().set("body", Json.obj().put("amount", 50))).allowed()).isTrue();

    var over = autonomyGate.evaluate(cato, "pay_supplier",
        Json.obj().set("body", Json.obj().put("amount", 5000)));
    assertThat(over.needsPermission()).isTrue();
    assertThat(over.reason()).contains("exceeds the configured limit");
  }

  @Test
  void withNoRulesAtAllTheConservativeDefaultStillApplies() {
    UUID cato = agentId("professional");
    for (JsonNode policy : api.get("/api/autonomy/policies").json().path("policies")) {
      api.delete("/api/autonomy/policies/" + policy.path("id").asText());
    }
    assertThat(api.get("/api/autonomy/policies").json().path("policies")).isEmpty();

    // Reading is still automatic; anything that changes the outside world still asks.
    var read = capabilities.invoke(cato,
        new ToolCall("c1", "knowledge_search", Json.obj().put("query", "filing")), true);
    assertThat(read.ok()).as(String.valueOf(read.error())).isTrue();

    var write = capabilities.invoke(cato, new ToolCall("c2", "schedule_task", Json.obj()
        .put("title", "Anything").put("instruction", "x")
        .put("run_at", Instant.now().plusSeconds(3600).toString())), true);
    assertThat(write.error()).startsWith("permission_required:");
  }

  @Test
  void aDisabledAgentDoesNothingAtAllEvenWithAnAllowRuleInItsFavour() {
    UUID vita = agentId("health");
    api.put("/api/autonomy/policies", Map.of(
        "agentId", vita.toString(), "toolRef", "*", "rule", "ALLOW", "precedence", 90));

    assertThat(api.patch("/api/agents/" + vita, Map.of("enabled", false)).ok()).isTrue();

    var result = capabilities.invoke(vita,
        new ToolCall("c1", "knowledge_search", Json.obj().put("query", "anything")), true);

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("disabled");
  }
}
