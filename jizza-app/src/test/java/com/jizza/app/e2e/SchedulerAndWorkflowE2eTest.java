package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.RunStepStatus;
import com.jizza.engine.api.enums.TaskStatus;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.repository.ScheduledTaskRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import com.jizza.engine.persistence.repository.WorkflowRunStepRepository;
import com.jizza.engine.scheduler.config.SchedulerProperties;
import com.jizza.engine.scheduler.run.TaskDispatcher;
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
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
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

  private ApiClient signedInAsSomeoneElse() {
    ApiClient other = new ApiClient(port);
    other.prime();
    String email = "other-" + UUID.randomUUID() + "@jizza.local";
    other.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    other.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    return other;
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
  void theRunHistoryListsEveryRunAndTracesEachStepWithWhatItTookAndGave() {
    model.scriptFor("Cato", LlmResponse.text("The total is 42."));
    UUID workflowId = approvedWorkflow("History", threeSteps());
    var run = api.post("/api/workflows/" + workflowId + "/run",
        Map.of("version", 1, "input", Json.obj().put("amount", 21)));
    UUID runId = UUID.fromString(run.json().path("id").asText());

    var page = api.get("/api/runs?workflowId=" + workflowId + "&status=DONE&trigger=manual"
        + "&from=" + Instant.now().minusSeconds(3600) + "&tz=Asia/Singapore");
    assertThat(page.ok()).as(page.body()).isTrue();
    assertThat(page.json().path("total").asInt()).isEqualTo(1);
    var item = page.json().path("items").get(0);
    assertThat(item.path("id").asText()).isEqualTo(runId.toString());
    assertThat(item.path("workflowName").asText()).isEqualTo("History");
    assertThat(item.path("triggerKind").asText()).isEqualTo("manual");
    assertThat(item.path("stepCount").asInt()).isEqualTo(4);
    assertThat(page.json().path("stats").path("successRate").asDouble()).isEqualTo(1.0);
    assertThat(page.json().path("buckets").size()).isPositive();

    var trace = api.get("/api/runs/" + runId + "/trace");
    assertThat(trace.ok()).as(trace.body()).isTrue();
    assertThat(trace.json().path("nodes").findValuesAsText("id"))
        .containsExactly("start", "double", "summarise", "finish");
    JsonNode doubled = null;
    for (JsonNode step : trace.json().path("steps")) {
      if (step.path("nodeId").asText().equals("double")) doubled = step;
    }
    assertThat(doubled).isNotNull();
    assertThat(doubled.path("type").asText()).isEqualTo("TRANSFORM");
    assertThat(doubled.path("output").asInt()).isEqualTo(42);
    assertThat(doubled.path("input").path("settings").path("expression").asText())
        .isEqualTo("input.amount * 2");
    assertThat(doubled.path("input").path("received").has("start")).isTrue();
    assertThat(trace.json().path("input").path("amount").asInt()).isEqualTo(21);

    // Someone else's run is not there to read.
    var other = signedInAsSomeoneElse();
    assertThat(other.get("/api/runs/" + runId + "/trace").status()).isEqualTo(404);
    assertThat(other.get("/api/runs").json().path("total").asInt()).isZero();

    // The same run shows up in the activity view, filed under workflows.
    var activity = api.get("/api/activity/explore?category=workflows&group=workflows/runs"
        + "&from=" + Instant.now().minusSeconds(3600));
    assertThat(activity.ok()).as(activity.body()).isTrue();
    assertThat(activity.json().path("items").findValuesAsText("action")).contains("run.completed");
    assertThat(activity.json().path("categories").findValuesAsText("key"))
        .contains("security", "workflows", "other");
    assertThat(api.get("/api/activity/catalog").json().path("categories").size()).isGreaterThan(5);

    var usage = api.get("/api/usage/explore?groupBy=MODEL&thenBy=AREA&from="
        + Instant.now().minusSeconds(3600));
    assertThat(usage.ok()).as(usage.body()).isTrue();
    assertThat(usage.json().path("totals").path("calls").asLong()).isPositive();
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
  void answeringAFailedStepWithStopEndsTheRunAndClearsTheQuestion() {
    model.fallback = messages -> {
      throw new IllegalStateException("upstream unavailable");
    };
    WorkflowGraph graph = new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            retrying("flaky", NodeType.AGENT_STEP, Json.obj()
                .put("agentId", agentId("professional").toString())
                .put("instruction", "try"), 1),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "flaky", null),
            new WorkflowEdge("flaky", "finish", null)));
    UUID workflowId = approvedWorkflow("Stoppable", graph);
    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    UUID runId = UUID.fromString(run.json().path("id").asText());
    assertThat(run.json().path("status").asText()).isEqualTo("PAUSED_WAITING_INSTRUCTION");
    var question = questionFor(runId);
    assertThat(question.path("context").path("failedStep").asBoolean()).isTrue();

    var answered = api.post("/api/notifications/" + question.path("id").asText() + "/answer",
        Map.of("kind", "SUBMIT_INSTRUCTION", "instruction", "Stop the run"));

    assertThat(answered.ok()).as(answered.body()).isTrue();
    assertThat(answered.json().path("status").asText()).isEqualTo("SUBMITTED");
    assertThat(api.get("/api/runs/" + runId).json().path("status").asText()).isEqualTo("CANCELLED");
    assertThat(api.get("/api/notifications/" + question.path("id").asText()).json()
        .path("status").asText()).isEqualTo("SUBMITTED");
  }

  @Test
  void stoppingARunFromTheRunViewClosesWhatItAsked() {
    model.fallback = messages -> {
      throw new IllegalStateException("upstream unavailable");
    };
    WorkflowGraph graph = new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            retrying("flaky", NodeType.AGENT_STEP, Json.obj()
                .put("agentId", agentId("professional").toString())
                .put("instruction", "try"), 1),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "flaky", null),
            new WorkflowEdge("flaky", "finish", null)));
    UUID workflowId = approvedWorkflow("Stopped from the run", graph);
    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    UUID runId = UUID.fromString(run.json().path("id").asText());
    var question = questionFor(runId);

    var stopped = api.post("/api/runs/" + runId + "/cancel", Map.of());
    assertThat(stopped.ok()).as(stopped.body()).isTrue();

    var closed = api.get("/api/notifications/" + question.path("id").asText()).json();
    assertThat(closed.path("status").asText()).isEqualTo("EXPIRED");
    assertThat(closed.path("responseNote").asText()).contains("stopped");

    // A stale screen that still shows the question gets a plain refusal; the item stays closed.
    var late = api.post("/api/notifications/" + question.path("id").asText() + "/answer",
        Map.of("kind", "SUBMIT_INSTRUCTION", "instruction", "Try again"));
    assertThat(late.status()).isEqualTo(400);
  }

  @Test
  void aQuestionLeftOpenByARunThatAlreadyEndedCanStillBeClearedByAnsweringIt() {
    model.fallback = messages -> {
      throw new IllegalStateException("upstream unavailable");
    };
    WorkflowGraph graph = new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            retrying("flaky", NodeType.AGENT_STEP, Json.obj()
                .put("agentId", agentId("professional").toString())
                .put("instruction", "try"), 1),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "flaky", null),
            new WorkflowEdge("flaky", "finish", null)));
    UUID workflowId = approvedWorkflow("Ended behind its question", graph);
    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    UUID runId = UUID.fromString(run.json().path("id").asText());
    var question = questionFor(runId);
    // As runs ended before questions were closed with them: the run is over, the item still open.
    var row = runs.findById(runId).orElseThrow();
    row.setStatus(com.jizza.engine.api.enums.RunStatus.CANCELLED);
    runs.save(row);

    var answered = api.post("/api/notifications/" + question.path("id").asText() + "/answer",
        Map.of("kind", "SUBMIT_INSTRUCTION", "instruction", "Stop and wait for me"));

    assertThat(answered.ok()).as(answered.body()).isTrue();
    assertThat(answered.json().path("status").asText()).isEqualTo("EXPIRED");
    assertThat(api.get("/api/notifications/" + question.path("id").asText()).json()
        .path("responseNote").asText()).contains("already ended");
  }

  private com.fasterxml.jackson.databind.JsonNode questionFor(UUID runId) {
    var pending = api.get("/api/notifications");
    return java.util.stream.StreamSupport.stream(pending.json().spliterator(), false)
        .filter(n -> n.path("kind").asText().equals(NotificationKind.INSTRUCTION_REQUIRED.name()))
        .filter(n -> n.path("runId").asText().equals(runId.toString()))
        .findFirst().orElseThrow();
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
    assertThat(steps.findByRunIdAndNodeId(highRun, "file").orElseThrow().getStatus())
        .as("the path not taken is recorded as skipped").isEqualTo(RunStepStatus.SKIPPED);

    var low = api.post("/api/workflows/" + workflowId + "/run",
        Map.of("version", 1, "input", Json.obj().put("total", 50)));
    UUID lowRun = UUID.fromString(low.json().path("id").asText());
    assertThat(steps.findByRunIdAndNodeId(lowRun, "file")).isPresent();
    assertThat(steps.findByRunIdAndNodeId(lowRun, "escalate").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.SKIPPED);
  }

  @Test
  void aParallelPathWaitsInTheDatabaseWhileTheOtherFinishes() {
    WorkflowGraph graph = new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("split", NodeType.PARALLEL, null),
            node("quick", NodeType.TRANSFORM, Json.obj().put("expression", "'done now'")),
            node("later", NodeType.WAIT, Json.obj().put("duration", "PT1H")),
            node("join", NodeType.JOIN, null),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "split", null),
            new WorkflowEdge("split", "quick", "a"), new WorkflowEdge("split", "later", "b"),
            new WorkflowEdge("quick", "join", null), new WorkflowEdge("later", "join", null),
            new WorkflowEdge("join", "finish", null)));
    UUID workflowId = approvedWorkflow("Parallel with a wait", graph);

    var run = api.post("/api/workflows/" + workflowId + "/run", Map.of("version", 1));
    assertThat(run.ok()).as(run.body()).isTrue();
    UUID runId = UUID.fromString(run.json().path("id").asText());

    var stored = api.get("/api/runs/" + runId).json();
    assertThat(stored.path("status").asText()).isEqualTo("WAITING");
    assertThat(stored.path("currentNode").asText()).isEqualTo("later");
    assertThat(steps.findByRunIdAndNodeId(runId, "quick").orElseThrow().getStatus())
        .isEqualTo(RunStepStatus.DONE);
    assertThat(steps.findByRunIdAndNodeId(runId, "join")).isEmpty();

    // A recovery sweep before the time comes leaves it waiting.
    engine.resumeAfterRestart(runId);
    assertThat(api.get("/api/runs/" + runId).json().path("status").asText()).isEqualTo("WAITING");

    engine.cancel(runId);
    assertThat(api.get("/api/runs/" + runId).json().path("status").asText()).isEqualTo("CANCELLED");
  }

  @Test
  void aWorkflowRunsAnotherAsAStepAndGetsItsResult() {
    UUID child = approvedWorkflow("Add tax", new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("tax", NodeType.TRANSFORM, Json.obj().put("expression", "input.item * 1.18")),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "tax", null), new WorkflowEdge("tax", "finish", null))));
    UUID parent = approvedWorkflow("Invoice totals", new WorkflowGraph(
        List.of(node("start", NodeType.TRIGGER, null),
            node("each", NodeType.FOR_EACH, Json.obj().put("workflowId", child.toString())
                .put("items", "input.amounts")),
            node("finish", NodeType.END, null)),
        List.of(new WorkflowEdge("start", "each", null), new WorkflowEdge("each", "finish", null))));

    var run = api.post("/api/workflows/" + parent + "/run",
        Map.of("version", 1, "input", Json.read("{\"amounts\":[100,200]}")));
    assertThat(run.ok()).as(run.body()).isTrue();
    UUID runId = UUID.fromString(run.json().path("id").asText());

    assertThat(run.json().path("status").asText()).isEqualTo("DONE");
    var results = steps.findByRunIdAndNodeId(runId, "each").orElseThrow().getOutput().path("results");
    assertThat(results.get(0).asDouble()).isEqualTo(118.0);
    assertThat(results.get(1).asDouble()).isEqualTo(236.0);
    assertThat(runs.findAll()).filteredOn(r -> r.getWorkflowId().equals(child)).hasSize(2);
  }

  @Test
  void builtInStepsApproveCallAWebhookKeepACountAndTellTheUserWithoutAnAgent() {
    try (StubService service = new StubService()) {
      WorkflowGraph graph = new WorkflowGraph(
          List.of(node("start", NodeType.TRIGGER, null),
              node("approve", NodeType.APPROVAL, Json.obj()
                  .put("prompt", "Add {{ input.name }} to the shop?")),
              node("hook", NodeType.HTTP_REQUEST, Json.obj().put("method", "POST")
                  .put("url", service.baseUrl() + "/v1/pets")
                  .put("bodyExpr", "{'name': input.name}")
                  .set("headers", Json.obj().put("Authorization", "Bearer {{ input.token }}"))),
              node("count", NodeType.STORAGE, Json.obj().put("action", "increment")
                  .put("key", "pets-added")),
              node("tell", NodeType.NOTIFY, Json.obj()
                  .put("message", "Added {{ nodes.hook.body.id }}, {{ nodes.count.value }} so far")),
              node("finish", NodeType.END, null)),
          List.of(new WorkflowEdge("start", "approve", null),
              new WorkflowEdge("approve", "hook", "approved"),
              new WorkflowEdge("hook", "count", null), new WorkflowEdge("count", "tell", null),
              new WorkflowEdge("tell", "finish", null)));
      UUID workflowId = approvedWorkflow("Add a pet", graph);

      var run = api.post("/api/workflows/" + workflowId + "/run",
          Map.of("version", 1, "input", Json.obj().put("name", "Rex").put("token", "t-1")));
      UUID runId = UUID.fromString(run.json().path("id").asText());
      assertThat(run.json().path("status").asText()).isEqualTo("PAUSED_WAITING_INSTRUCTION");
      assertThat(service.petsCreated.get()).as("nothing is sent before approval").isZero();

      var request = java.util.stream.StreamSupport.stream(
              api.get("/api/notifications?unreadOnly=true").json().spliterator(), false)
          .filter(n -> n.path("runId").asText().equals(runId.toString()))
          .findFirst().orElseThrow();
      assertThat(request.path("kind").asText()).isEqualTo("PERMISSION_REQUEST");
      assertThat(request.path("body").asText()).isEqualTo("Add Rex to the shop?");

      var answered = api.post("/api/notifications/" + request.path("id").asText() + "/answer",
          Map.of("kind", "APPROVE"));
      assertThat(answered.ok()).as(answered.body()).isTrue();

      assertThat(api.get("/api/runs/" + runId).json().path("status").asText()).isEqualTo("DONE");
      assertThat(service.petsCreated.get()).isEqualTo(1);
      assertThat(service.lastBearerToken()).isEqualTo("t-1");
      assertThat(steps.findByRunIdAndNodeId(runId, "count").orElseThrow().getOutput()
          .path("value").asLong()).isEqualTo(1);
      assertThat(api.get("/api/notifications").json().toString()).contains("Added pet-1, 1 so far");
    }
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
        .isInstanceOf(com.jizza.engine.api.error.ValidationException.class)
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
