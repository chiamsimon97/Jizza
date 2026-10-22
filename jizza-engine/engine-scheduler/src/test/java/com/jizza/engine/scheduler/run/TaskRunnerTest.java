package com.jizza.engine.scheduler.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.ScheduleType;
import com.jizza.engine.api.enums.TaskStatus;
import com.jizza.engine.api.error.AutonomyDeniedException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.persistence.entity.ScheduledTaskEntity;
import com.jizza.engine.persistence.repository.ScheduledTaskRepository;
import com.jizza.engine.scheduler.config.SchedulerProperties;
import com.jizza.engine.scheduler.recurrence.RecurrenceCalculator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class TaskRunnerTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID agentId = UUID.randomUUID();

  private ScheduledTaskEntity task;
  private ScheduledTaskRepository tasks;
  private Agent baseAgent;
  private WorkflowEngine engine;
  private KillSwitch killSwitch;
  private AutonomyGate gate;
  private AuditLog audit;
  private TaskRunner runner;
  private org.springframework.context.ApplicationEventPublisher events;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    task = new ScheduledTaskEntity();
    task.setId(UUID.randomUUID());
    task.setUserId(userId);
    task.setAgentId(agentId);
    task.setTitle("Weekly finance review");
    task.setPayload(Json.obj().put("instruction", "Summarise last week's spending"));
    task.setScheduleType(ScheduleType.RECURRING);
    task.setCronOrRrule("0 0 9 * * MON");
    task.setTimezone("Asia/Kolkata");
    task.setStatus(TaskStatus.PENDING);
    task.setNextRunAt(Instant.now().minusSeconds(5));
    task.setCreatedAt(Instant.now().minusSeconds(86_400));

    tasks = mock(ScheduledTaskRepository.class);
    when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
    when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));

    baseAgent = mock(Agent.class);
    when(baseAgent.run(any())).thenReturn(AgentResult.of(agentId, "Kai", "Spending was steady."));

    engine = mock(WorkflowEngine.class);
    ObjectProvider<WorkflowEngine> engineProvider = mock(ObjectProvider.class);
    when(engineProvider.getIfAvailable()).thenReturn(engine);

    killSwitch = mock(KillSwitch.class);
    gate = mock(AutonomyGate.class);
    when(gate.agentEnabled(any())).thenReturn(true);
    audit = mock(AuditLog.class);

    ObjectProvider<com.jizza.engine.api.procedure.BindingEnforcer> enforcerProvider =
        mock(ObjectProvider.class);
    when(enforcerProvider.getIfAvailable()).thenReturn(null);
    events = mock(org.springframework.context.ApplicationEventPublisher.class);

    runner = new TaskRunner(tasks, baseAgent, engineProvider, new RecurrenceCalculator(),
        killSwitch, gate, audit, new SchedulerProperties(), enforcerProvider, events);
  }

  @Test
  void runsAnUnboundTaskThroughTheOwningAgentAsAutonomousWork() {
    runner.run(task.getId());

    ArgumentCaptor<AgentRequest> request = ArgumentCaptor.forClass(AgentRequest.class);
    verify(baseAgent).run(request.capture());
    assertThat(request.getValue().agentId()).isEqualTo(agentId);
    assertThat(request.getValue().instruction()).isEqualTo("Summarise last week's spending");
    assertThat(request.getValue().autonomous()).as("nobody is watching this run").isTrue();
    assertThat(task.getLastResult()).isEqualTo("Spending was steady.");
  }

  @Test
  void aBoundTaskRunsTheApprovedProcedureAndNeverImprovises() {
    UUID workflowId = UUID.randomUUID();
    task.setBoundWorkflowId(workflowId);
    task.setBoundVersion(3);
    when(engine.start(eq(workflowId), eq(3), any(), any())).thenReturn(runDto(workflowId, 3));

    runner.run(task.getId());

    verify(engine).start(eq(workflowId), eq(3), eq(task.getPayload()),
        eq("scheduled_task:" + task.getId()));
    verify(baseAgent, never()).run(any());
    assertThat(task.getLastResult()).contains("Ran procedure version 3");
  }

  @Test
  void theKillSwitchStopsTheRunAndLeavesTheScheduleIntact() {
    org.mockito.Mockito.doThrow(new AutonomyDeniedException("Autonomy is paused"))
        .when(killSwitch).requireActive(userId);

    runner.run(task.getId());

    verify(baseAgent, never()).run(any());
    assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(task.getLastResult()).contains("Skipped");
    verify(audit).record(eq(userId), any(), any(), eq("task.skipped"), any(), any());
  }

  @Test
  void aDisabledOwningAgentAlsoStopsTheRun() {
    when(gate.agentEnabled(agentId)).thenReturn(false);

    runner.run(task.getId());

    verify(baseAgent, never()).run(any());
    assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void aRecurringTaskGetsItsNextOccurrenceAfterEveryRun() {
    runner.run(task.getId());

    assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(task.getNextRunAt()).isAfter(Instant.now());
    assertThat(task.getLastRunAt()).isNotNull();
  }

  @Test
  void aOneTimeTaskRetiresAfterItRuns() {
    task.setScheduleType(ScheduleType.ONE_TIME);
    task.setCronOrRrule(null);
    task.setRunAt(task.getNextRunAt());

    runner.run(task.getId());

    assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
    assertThat(task.getNextRunAt()).isNull();
  }

  @Test
  void aOneTimeTaskThatWasSkippedKeepsItsSlot() {
    task.setScheduleType(ScheduleType.ONE_TIME);
    task.setCronOrRrule(null);
    Instant due = task.getNextRunAt();
    org.mockito.Mockito.doThrow(new AutonomyDeniedException("Autonomy is paused"))
        .when(killSwitch).requireActive(userId);

    runner.run(task.getId());

    assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(task.getNextRunAt()).isEqualTo(due);
  }

  @Test
  void aFailingRunIsRecordedAndTheScheduleContinues() {
    org.mockito.Mockito.doThrow(new IllegalStateException("connector down"))
        .when(baseAgent).run(any());

    runner.run(task.getId());

    assertThat(task.getLastResult()).contains("connector down");
    assertThat(task.getNextRunAt()).isAfter(Instant.now());
    verify(audit).record(eq(userId), any(), any(), eq("task.failed"), any(), any());
  }

  @Test
  void aRepetitiveTaskWithNoProcedureBoundAnnouncesItselfForLearning() {
    task.setRepetitive(true);

    runner.run(task.getId());

    verify(events).publishEvent(org.mockito.ArgumentMatchers
        .any(com.jizza.engine.api.event.RepetitiveTaskCompleted.class));
  }

  @Test
  void anOrdinaryTaskDoesNotAnnounceAnythingToLearn() {
    runner.run(task.getId());
    verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void claimingIsWhatStopsASecondWorkerRunningTheSameTask() {
    assertThat(runner.claim(task.getId())).isTrue();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);

    // A second worker looking at the same row now sees it as running and declines.
    assertThat(runner.claim(task.getId())).isFalse();
  }

  @Test
  void aTaskThatIsNotYetDueIsNotClaimed() {
    task.setNextRunAt(Instant.now().plusSeconds(600));
    assertThat(runner.claim(task.getId())).isFalse();
    assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void anUnknownTaskIsNotClaimedAndDoesNotThrow() {
    assertThat(runner.claim(UUID.randomUUID())).isFalse();
    runner.run(UUID.randomUUID());
    verify(baseAgent, never()).run(any());
  }

  private RunDto runDto(UUID workflowId, int version) {
    return new RunDto(UUID.randomUUID(), userId, workflowId, version, "scheduled_task",
        RunStatus.DONE, null, null, Instant.now(), Instant.now(), List.of());
  }

  // ── reusing a saved workflow ──────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private com.jizza.engine.api.notification.NotificationBus withLibrary(
      com.jizza.engine.api.workflow.library.WorkflowLibrary library) {
    ObjectProvider<com.jizza.engine.api.workflow.library.WorkflowLibrary> libraries =
        mock(ObjectProvider.class);
    when(libraries.getIfAvailable()).thenReturn(library);
    var bus = mock(com.jizza.engine.api.notification.NotificationBus.class);
    ObjectProvider<com.jizza.engine.api.notification.NotificationBus> buses = mock(ObjectProvider.class);
    when(buses.getIfAvailable()).thenReturn(bus);
    runner.setLibrary(libraries, buses);
    return bus;
  }

  @Test
  void aTaskASavedWorkflowDoesRunsThatWorkflowAndTellsTheUser() {
    var library = mock(com.jizza.engine.api.workflow.library.WorkflowLibrary.class);
    UUID workflowId = UUID.randomUUID();
    when(library.find(eq(userId), eq("Summarise last week's spending"),
        eq(com.jizza.engine.api.workflow.library.WorkflowLibrary.Use.SCHEDULE), eq(1)))
        .thenReturn(List.of(new com.jizza.engine.api.workflow.library.WorkflowLibrary.Match(
            workflowId, "Weekly spending summary", null,
            com.jizza.engine.api.enums.WorkflowStatus.APPROVED, 1, null, 0.8, List.of())));
    UUID runId = UUID.randomUUID();
    when(library.reuse(eq(userId), eq(workflowId), any(),
        eq(com.jizza.engine.api.workflow.library.WorkflowLibrary.Use.SCHEDULE),
        eq("scheduled_task:" + task.getId()), eq(false)))
        .thenReturn(new RunDto(runId, userId, workflowId, 1, "library:scheduled_task", RunStatus.DONE,
            null, Json.obj(), Instant.now(), Instant.now(), List.of()));
    task.setRepetitive(true);
    var bus = withLibrary(library);

    runner.run(task.getId());

    verify(baseAgent, never()).run(any());
    assertThat(task.getLastResult()).contains("Weekly spending summary");
    verify(bus).raise(eq(com.jizza.engine.api.enums.NotificationKind.INFO), eq(agentId), any());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test
  void whenItsGuardrailsSayNoTheAgentDoesTheJobAsBefore() {
    var library = mock(com.jizza.engine.api.workflow.library.WorkflowLibrary.class);
    when(library.find(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(new com.jizza.engine.api.workflow.library.WorkflowLibrary.Match(
            UUID.randomUUID(), "Weekly spending summary", null,
            com.jizza.engine.api.enums.WorkflowStatus.APPROVED, 1, null, 0.8, List.of())));
    when(library.reuse(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenThrow(new com.jizza.engine.api.error.ValidationException("its limit"));
    withLibrary(library);

    runner.run(task.getId());

    verify(baseAgent).run(any());
    assertThat(task.getLastResult()).isEqualTo("Spending was steady.");
  }
}
