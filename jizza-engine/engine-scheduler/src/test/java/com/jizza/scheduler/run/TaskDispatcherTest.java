package com.jizza.scheduler.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.common.enums.CatchUpPolicy;
import com.jizza.common.enums.ScheduleType;
import com.jizza.common.enums.TaskStatus;
import com.jizza.persistence.entity.ScheduledTaskEntity;
import com.jizza.persistence.repository.ScheduledTaskRepository;
import com.jizza.scheduler.config.SchedulerProperties;
import com.jizza.scheduler.recurrence.RecurrenceCalculator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskDispatcherTest {

  private ScheduledTaskRepository tasks;
  private TaskRunner runner;
  private SchedulerProperties props;
  private TaskDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    tasks = mock(ScheduledTaskRepository.class);
    runner = mock(TaskRunner.class);
    props = new SchedulerProperties();
    when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(tasks.findByStatus(any())).thenReturn(List.of());
    dispatcher = new TaskDispatcher(tasks, runner, new RecurrenceCalculator(), props);
  }

  private ScheduledTaskEntity task(Instant nextRunAt, ScheduleType type) {
    ScheduledTaskEntity t = new ScheduledTaskEntity();
    t.setId(UUID.randomUUID());
    t.setUserId(UUID.randomUUID());
    t.setAgentId(UUID.randomUUID());
    t.setTitle("t");
    t.setScheduleType(type);
    t.setCronOrRrule(type == ScheduleType.RECURRING ? "0 0 9 * * *" : null);
    t.setTimezone("Asia/Kolkata");
    t.setStatus(TaskStatus.PENDING);
    t.setNextRunAt(nextRunAt);
    t.setCatchUpPolicy(CatchUpPolicy.RUN_ONCE);
    t.setCreatedAt(Instant.now().minus(Duration.ofDays(30)));
    return t;
  }

  @Test
  void runsOnlyTasksItSuccessfullyClaims() {
    var claimed = task(Instant.now().minusSeconds(5), ScheduleType.RECURRING);
    var lost = task(Instant.now().minusSeconds(5), ScheduleType.RECURRING);
    when(tasks.findDue(any(), any())).thenReturn(List.of(claimed, lost));
    when(runner.claim(claimed.getId())).thenReturn(true);
    when(runner.claim(lost.getId())).thenReturn(false);

    dispatcher.dispatchDue();

    verify(runner).run(claimed.getId());
    verify(runner, never()).run(lost.getId());
  }

  @Test
  void aFailingRunDoesNotStopTheRestOfTheBatch() {
    var first = task(Instant.now().minusSeconds(5), ScheduleType.RECURRING);
    var second = task(Instant.now().minusSeconds(5), ScheduleType.RECURRING);
    when(tasks.findDue(any(), any())).thenReturn(List.of(first, second));
    when(runner.claim(any())).thenReturn(true);
    org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(runner).run(first.getId());

    dispatcher.dispatchDue();

    verify(runner).run(second.getId());
  }

  @Test
  void doesNothingWhenTheSchedulerIsSwitchedOff() {
    props.setEnabled(false);
    dispatcher.dispatchDue();
    verify(tasks, never()).findDue(any(), any());
  }

  @Test
  void reclaimsTasksLeftRunningByAProcessThatDied() {
    var stuck = task(Instant.now().minusSeconds(60), ScheduleType.RECURRING);
    stuck.setStatus(TaskStatus.RUNNING);
    stuck.setLastRunAt(Instant.now().minus(Duration.ofHours(2)));
    when(tasks.findByStatus(TaskStatus.RUNNING)).thenReturn(List.of(stuck));

    dispatcher.reconcileOnStartup();

    assertThat(stuck.getStatus()).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void leavesATaskThatIsGenuinelyStillRunningAlone() {
    var running = task(Instant.now(), ScheduleType.RECURRING);
    running.setStatus(TaskStatus.RUNNING);
    running.setLastRunAt(Instant.now().minusSeconds(5));
    when(tasks.findByStatus(TaskStatus.RUNNING)).thenReturn(List.of(running));

    dispatcher.reconcileOnStartup();

    assertThat(running.getStatus()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void settlesMissedRecurringRunsUnderTheTasksCatchUpPolicy() {
    var missed = task(Instant.now().minus(Duration.ofDays(3)), ScheduleType.RECURRING);
    when(tasks.findByStatus(TaskStatus.PENDING)).thenReturn(List.of(missed));

    dispatcher.reconcileOnStartup();

    // RUN_ONCE acknowledges the missed window immediately rather than replaying three days of runs.
    assertThat(missed.getNextRunAt()).isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(5));
  }

  @Test
  void skipPolicyJumpsPastTheBacklogEntirely() {
    var missed = task(Instant.now().minus(Duration.ofDays(3)), ScheduleType.RECURRING);
    missed.setCatchUpPolicy(CatchUpPolicy.SKIP);
    when(tasks.findByStatus(TaskStatus.PENDING)).thenReturn(List.of(missed));

    dispatcher.reconcileOnStartup();

    assertThat(missed.getNextRunAt()).isAfter(Instant.now());
  }

  @Test
  void aMissedOneTimeTaskIsLeftForTheNextPollRatherThanRescheduled() {
    var missed = task(Instant.now().minus(Duration.ofDays(1)), ScheduleType.ONE_TIME);
    Instant due = missed.getNextRunAt();
    when(tasks.findByStatus(TaskStatus.PENDING)).thenReturn(List.of(missed));

    dispatcher.reconcileOnStartup();

    assertThat(missed.getNextRunAt()).isEqualTo(due);
    verify(tasks, never()).save(missed);
  }

  @Test
  void aFutureTaskIsNotTouchedOnBoot() {
    var future = task(Instant.now().plus(Duration.ofHours(2)), ScheduleType.RECURRING);
    Instant due = future.getNextRunAt();
    when(tasks.findByStatus(TaskStatus.PENDING)).thenReturn(List.of(future));

    dispatcher.reconcileOnStartup();

    assertThat(future.getNextRunAt()).isEqualTo(due);
  }
}
