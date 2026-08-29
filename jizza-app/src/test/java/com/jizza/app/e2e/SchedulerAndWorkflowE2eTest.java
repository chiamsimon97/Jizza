package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.dto.AgentDto;
import com.jizza.common.enums.NodeType;
import com.jizza.common.enums.NotificationKind;
import com.jizza.common.enums.RunStatus;
import com.jizza.common.enums.RunStepStatus;
import com.jizza.common.enums.TaskStatus;
import com.jizza.common.enums.WorkflowStatus;
import com.jizza.common.llm.LlmResponse;
import com.jizza.common.orchestrator.AgentDirectory;
import com.jizza.common.util.Json;
import com.jizza.common.workflow.WorkflowEngine;
import com.jizza.common.workflow.graph.WorkflowEdge;
import com.jizza.common.workflow.graph.WorkflowGraph;
import com.jizza.common.workflow.graph.WorkflowNode;
import com.jizza.persistence.repository.ScheduledTaskRepository;
import com.jizza.persistence.repository.WorkflowRepository;
import com.jizza.persistence.repository.WorkflowRunRepository;
import com.jizza.persistence.repository.WorkflowRunStepRepository;
import com.jizza.scheduler.config.SchedulerProperties;
import com.jizza.scheduler.run.TaskDispatcher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Acceptance: tasks fire unattended without double-firing, and workflow runs are durable,
 * resumable, exactly-once and interruptible by a human (build prompt Acceptance Criteria 9, 10
 * and 13).
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class SchedulerAndWorkflowE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private AgentDirectory agents;
  @Autowired private TaskDispatcher dispatcher;
  @Autowired private SchedulerProperties schedulerProps;
  @Autowired private ScheduledTaskRepository tasks;
  @Autowired private WorkflowRepository workflows;
  @Autowired private WorkflowRunRepository runs;
  @Autowired private WorkflowRunStepRepository steps;
  @Autowired private WorkflowEngine engine;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient api;
  private UUID userId;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();

    String email = "sched-" + UUID.randomUUID() + "@jizza.local";
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

  /** One dispatcher tick, with the inter-tick lock hold cleared first. */
  private void dispatch() {
    releaseDispatchLock();
    dispatcher.dispatchDue();
  }

  private UUID agentId(String key) {
    return agents.byKey(userId, key).map(AgentDto::id).orElseThrow();
  }

  private WorkflowNode node(String id, NodeType type, JsonNode config) {
    return new WorkflowNode(id, type, id, config, null);
  }

  private WorkflowNode retrying(String id, NodeType type, JsonNode config, int attempts) {
    return new WorkflowNode(id, type, id, config,
        new WorkflowNode.RetryPolicy(attempts, 1, 1.0, 50));
  }

  /** Saves a workflow through the API and marks it approved so it can run unattended. */
  private UUID approvedWorkflow(String name, WorkflowGraph graph) {
    var saved = api.post("/api/workflows", Map.of("name", name, "graph", graph.toJson()));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    UUID id = UUID.fromString(saved.json().path("id").asText());

    var entity = workflows.findById(id).orElseThrow();
    entity.setStatus(WorkflowStatus.APPROVED);
    entity.setOwningAgentId(agentId("professional"));
    workflows.save(entity);
    return id;
  }

  // ── the scheduler ────────────────────────────────────────────────────────

  @Test
  void aOneTimeTaskFiresWithNoBrowserOpenAndThenRetires() {
    model.scriptFor("Cato", LlmResponse.text("Filed the expenses."));

    var created = api.post("/api/tasks", Map.of(
        "title", "File expenses", "agentId", agentId("professional").toString(),
        "payload", Json.obj().put("instruction", "file the expenses"),
        "scheduleType", "ONE_TIME", "runAt", Instant.now().minusSeconds(1).toString()));
    assertThat(created.ok()).as(created.body()).isTrue();
    UUID taskId = UUID.fromString(created.json().path("id").asText());

    dispatch();

    var task = tasks.findById(taskId).orElseThrow();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
    assertThat(task.getLastResult()).isEqualTo("Filed the expenses.");
    assertThat(task.getNextRunAt()).isNull();
  }

  @Test
  void aRecurringTaskFiresAndScheduleItselfAgain() {
    model.scriptFor("Cato", LlmResponse.text("Reviewed."),
        LlmResponse.text("Reviewed again."));

    var created = api.post("/api/tasks", Map.of(
        "title", "Weekly review", "agentId", agentId("professional").toString(),
        "payload", Json.obj().put("instruction", "review the week"),
        "scheduleType", "RECURRING", "cronOrRrule", "0 0 9 * * MON",
        "timezone", "Asia/Kolkata"));
    UUID taskId = UUID.fromString(created.json().path("id").asText());

    // Wind it due, then let the dispatcher pick it up the way the worker would.
    var task = tasks.findById(taskId).orElseThrow();
    task.setNextRunAt(Instant.now().minusSeconds(1));
    tasks.save(task);

    dispatch();

    task = tasks.findById(taskId).orElseThrow();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(task.getLastRunAt()).isNotNull();
    assertThat(task.getNextRunAt()).as("the next Monday is already scheduled")
        .isAfter(Instant.now());
  }

  @Test
  void twoWorkersPollingTogetherRunTheTaskExactlyOnce() throws Exception {
    model.scriptFor("Cato", LlmResponse.text("done once"));

    // A dispatch pass runs whatever else is due, so the instruction is made unique and the turns
    // are counted by it rather than by the agent's name.
    String instruction = "settle the invoice " + UUID.randomUUID();
    var created = api.post("/api/tasks", Map.of(
        "title", "Race", "agentId", agentId("professional").toString(),
        "payload", Json.obj().put("instruction", instruction),
        "scheduleType", "ONE_TIME", "runAt", Instant.now().minusSeconds(1).toString()));
    UUID taskId = UUID.fromString(created.json().path("id").asText());

    // Two dispatch passes at once, as two worker processes would.
    releaseDispatchLock();
    var first = java.util.concurrent.CompletableFuture.runAsync(dispatcher::dispatchDue);
    var second = java.util.concurrent.CompletableFuture.runAsync(dispatcher::dispatchDue);
    java.util.concurrent.CompletableFuture.allOf(first, second).join();

    long turnsForThisTask = model.requests.stream()
        .filter(conversation -> conversation.stream()
            .anyMatch(message -> message.text() != null && message.text().contains(instruction)))
        .count();
    assertThat(turnsForThisTask).as("the owning agent ran it once, not twice").isEqualTo(1);
    assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
  }

  @Test
  void aMissedRecurringRunIsReconciledOnBootRatherThanReplayingTheBacklog() {
    var created = api.post("/api/tasks", Map.of(
        "title", "Daily", "agentId", agentId("professional").toString(),
        "payload", Json.obj().put("instruction", "daily"),
        "scheduleType", "RECURRING", "cronOrRrule", "0 0 9 * * *", "timezone", "Asia/Kolkata"));
    UUID taskId = UUID.fromString(created.json().path("id").asText());

    // The process was down for three days.
    var task = tasks.findById(taskId).orElseThrow();
    task.setNextRunAt(Instant.now().minus(java.time.Duration.ofDays(3)));
    tasks.save(task);

    dispatcher.reconcileOnStartup();

    task = tasks.findById(taskId).orElseThrow();
    assertThat(task.getNextRunAt())
        .as("the default catch-up policy acknowledges the miss once rather than firing three times")
        .isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(30));
  }

  @Test
  void aScheduleIsRejectedIfItCannotBeParsed() {
    var refused = api.post("/api/tasks", Map.of(
        "title", "Nonsense", "agentId", agentId("professional").toString(),
        "scheduleType", "RECURRING", "cronOrRrule", "every other tuesday-ish"));
    assertThat(refused.status()).isEqualTo(400);
  }

  @Test
  void cancellingATaskStopsItFiring() {
    var created = api.post("/api/tasks", Map.of(
        "title", "Cancel me", "agentId", agentId("professional").toString(),
        "payload", Json.obj().put("instruction", "x"),
        "scheduleType", "ONE_TIME", "runAt", Instant.now().minusSeconds(1).toString()));
    UUID taskId = UUID.fromString(created.json().path("id").asText());

    assertThat(api.delete("/api/tasks/" + taskId).ok()).isTrue();
    dispatch();

    var task = tasks.findById(taskId).orElseThrow();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(task.getLastRunAt()).isNull();
  }

  // ── the durable engine ───────────────────────────────────────────────────

  private WorkflowGraph threeSteps() {
    return new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("double", NodeType.TRANSFORM, Json.obj().put("expression", "input.amount * 2")),
            node("summarise", NodeType.AGENT_STEP, Json.obj()
                .put("agentId", agentId("professional").toString())
                .put("instruction", "summarise the total")),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "double", null),
            new WorkflowEdge("double", "summarise", null),
            new WorkflowEdge("summarise", "finish", null)));
  }

  @Test
  void aWorkflowRunsToCompletionAndCheckpointsEveryNode() {
    model.scriptFor("Cato", LlmResponse.text("The total is 42."));
    UUID workflowId = approvedWorkflow("Total", threeSteps());

    var run = api.post("/api/workflows/" + workflowId + "/run",
        Map.of("version", 1, "input", Json.obj().put("amount", 21)));

    assertThat(run.ok()).as(run.body()).isTrue();
    assertThat(run.json().path("status").asText()).isEqualTo("DONE");
    assertThat(run.json().path("steps").findValuesAsText("nodeId"))
        .containsExactlyInAnyOrder("start", "double", "summarise", "finish");

    UUID runId = UUID.fromString(run.json().path("id").asText());
    assertThat(steps.findByRunIdAndNodeId(runId, "double").orElseThrow().getOutput().asInt())
        .isEqualTo(42);
  }

  @Test
  void aRunInterruptedMidFlightResumesFromItsLastCompletedNode() {
    // The agent step dies the way a killed process would: no checkpoint is written for it.
    model.fallback = messages -> {
      throw new IllegalStateException("the process died here");
    };
    UUID workflowId = approvedWorkflow("Interrupted", threeSteps());

    var run = api.post("/api/workflows/" + workflowId + "/run",
        Map.of("version", 1, "input", Json.obj().put("amount", 21)));
    UUID runId = UUID.fromString(run.json().path("id").asText());

    assertThat(steps.findByRunIdAndNodeId(runId, "double").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.DONE);
    JsonNode doubledBefore = steps.findByRunIdAndNodeId(runId, "double").orElseThrow().getOutput();

    // A fresh process picks it up, exactly as RunRecovery does on boot.
    var entity = runs.findById(runId).orElseThrow();
    entity.setStatus(RunStatus.RUNNING);
    runs.save(entity);
    model.reset();
    model.scriptFor("Cato", LlmResponse.text("Recovered and finished."));

    var resumed = engine.resumeAfterRestart(runId);

    assertThat(resumed.status()).isEqualTo(RunStatus.DONE);
    assertThat(steps.findByRunIdAndNodeId(runId, "double").orElseThrow().getOutput())
        .as("the completed node kept its original result rather than being recomputed")
        .isEqualTo(doubledBefore);
  }

  @Test
  void aFailingStepRetriesAndThenRaisesAnInterventionRatherThanDyingSilently() {
    model.fallback = messages -> {
      throw new IllegalStateException("upstream unavailable");
    };
    WorkflowGraph graph = new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            retrying("flaky", NodeType.AGENT_STEP, Json.obj()
                .put("agentId", agentId("professional").toString())
                .put("instruction", "try"), 3),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "flaky", null),
            new WorkflowEdge("flaky", "finish", null)));
    UUID workflowId = approvedWorkflow("Flaky", graph);

    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    UUID runId = UUID.fromString(run.json().path("id").asText());

    assertThat(run.json().path("status").asText()).isEqualTo("PAUSED_WAITING_INSTRUCTION");
    assertThat(steps.findByRunIdAndNodeId(runId, "flaky").orElseThrow().getAttempts()).isEqualTo(3);

    var notifications = api.get("/api/notifications");
    assertThat(notifications.json().findValuesAsText("kind")).contains("INSTRUCTION_REQUIRED");
    assertThat(notifications.json().toString()).contains("3 attempts");
  }

  @Test
  void anInterventionPausesTheRunAndItSurvivesARestartBeforeResuming() {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("ask", NodeType.HUMAN_INTERVENTION,
                Json.obj().put("prompt", "Which supplier should I use?")),
            node("use", NodeType.TRANSFORM, Json.obj().put("expression", "instructions.ask")),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "ask", null),
            new WorkflowEdge("ask", "use", null),
            new WorkflowEdge("use", "finish", null)));
    UUID workflowId = approvedWorkflow("Needs a decision", graph);

    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    UUID runId = UUID.fromString(run.json().path("id").asText());

    assertThat(run.json().path("status").asText()).isEqualTo("PAUSED_WAITING_INSTRUCTION");
    assertThat(run.json().path("currentNode").asText()).isEqualTo("ask");

    var pending = api.get("/api/notifications?unreadOnly=true");
    var intervention = java.util.stream.StreamSupport.stream(pending.json().spliterator(), false)
        .filter(n -> n.path("kind").asText().equals(NotificationKind.INSTRUCTION_REQUIRED.name()))
        .filter(n -> n.path("runId").asText().equals(runId.toString()))
        .findFirst().orElseThrow();
    assertThat(intervention.path("body").asText()).isEqualTo("Which supplier should I use?");

    // A restart sweep must leave a paused run exactly where it is.
    engine.resumeAfterRestart(runId);
    assertThat(api.get("/api/runs/" + runId).json().path("status").asText())
        .isEqualTo("PAUSED_WAITING_INSTRUCTION");

    var answered = api.post("/api/notifications/" + intervention.path("id").asText() + "/answer",
        Map.of("kind", "SUBMIT_INSTRUCTION", "instruction", "Use Acme Ltd"));
    assertThat(answered.ok()).as(answered.body()).isTrue();

    assertThat(api.get("/api/runs/" + runId).json().path("status").asText()).isEqualTo("DONE");
    assertThat(steps.findByRunIdAndNodeId(runId, "use").orElseThrow().getOutput().asText())
        .as("the instruction is fed downstream").isEqualTo("Use Acme Ltd");
  }

  @Test
  void aBranchTakesTheOutletItsConditionSelects() {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("big", NodeType.BRANCH, Json.obj().put("condition", "input.total > 1000")),
            node("escalate", NodeType.TRANSFORM, Json.obj().put("expression", "'escalated'")),
            node("file", NodeType.TRANSFORM, Json.obj().put("expression", "'filed'")),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "big", null),
            new WorkflowEdge("big", "escalate", "true"),
            new WorkflowEdge("big", "file", "false"),
            new WorkflowEdge("escalate", "finish", null),
            new WorkflowEdge("file", "finish", null)));
    UUID workflowId = approvedWorkflow("Branching", graph);

    var high = api.post("/api/workflows/" + workflowId + "/run",
        Map.of("version", 1, "input", Json.obj().put("total", 5000)));
    UUID highRun = UUID.fromString(high.json().path("id").asText());
    assertThat(steps.findByRunIdAndNodeId(highRun, "escalate")).isPresent();
    assertThat(steps.findByRunIdAndNodeId(highRun, "file")).isEmpty();

    var low = api.post("/api/workflows/" + workflowId + "/run",
        Map.of("version", 1, "input", Json.obj().put("total", 50)));
    UUID lowRun = UUID.fromString(low.json().path("id").asText());
    assertThat(steps.findByRunIdAndNodeId(lowRun, "file")).isPresent();
    assertThat(steps.findByRunIdAndNodeId(lowRun, "escalate")).isEmpty();
  }

  @Test
  void aTransformCannotEscapeItsSandboxAndTheRunSaysSo() {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("escape", NodeType.TRANSFORM,
                Json.obj().put("expression", "new('java.io.File', '/etc/passwd').exists()")
                    .put("onFailure", "dead_letter")),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "escape", null),
            new WorkflowEdge("escape", "finish", null)));
    UUID workflowId = approvedWorkflow("Escape attempt", graph);

    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));

    assertThat(run.json().path("status").asText()).isEqualTo("FAILED");
    UUID runId = UUID.fromString(run.json().path("id").asText());
    assertThat(runs.findById(runId).orElseThrow().isDeadLettered()).isTrue();
    assertThat(steps.findByRunIdAndNodeId(runId, "escape").orElseThrow().getAttempts())
        .as("a sandbox violation is permanent, so it is not retried").isEqualTo(1);
  }

  @Test
  void anUnapprovedWorkflowCannotBeRunUnattendedButCanBeTestedFromTheBuilder() {
    var saved = api.post("/api/workflows",
        Map.of("name", "Draft", "graph", threeSteps().toJson()));
    UUID workflowId = UUID.fromString(saved.json().path("id").asText());
    assertThat(saved.json().path("status").asText()).isEqualTo("DRAFT");
    model.scriptFor("Cato", LlmResponse.text("tested"));

    // The builder's own run is manual, and permitted.
    var manual = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    assertThat(manual.ok()).as(manual.body()).isTrue();

    // Unattended, it is refused.
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            engine.start(workflowId, 1, Json.obj(), "scheduled_task:whatever"))
        .isInstanceOf(com.jizza.common.error.ValidationException.class)
        .hasMessageContaining("not approved");
  }

  @Test
  void aSkillIsInvocableOnlyOnceItsWorkflowVersionIsApproved() {
    UUID workflowId = approvedWorkflow("Reusable", threeSteps());
    var skill = api.post("/api/workflows/" + workflowId + "/save-skill",
        Map.of("name", "File the total", "description", "Totals and files"));

    assertThat(skill.ok()).as(skill.body()).isTrue();
    assertThat(skill.json().path("invocable").asBoolean()).isTrue();

    var entity = workflows.findById(workflowId).orElseThrow();
    entity.setStatus(WorkflowStatus.PENDING_REVIEW);
    workflows.save(entity);

    var draftSkill = api.post("/api/workflows/" + workflowId + "/save-skill",
        Map.of("name", "File the total", "description", "Totals and files"));
    assertThat(draftSkill.json().path("invocable").asBoolean())
        .as("an unapproved version is not callable").isFalse();
  }
}
