package com.jizza.platform.usecase.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.TaskDto;
import com.jizza.engine.api.enums.ScheduleType;
import com.jizza.engine.api.enums.TaskStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.scheduler.SchedulerService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Cancel and mark-repetitive: ownership is checked and the outcome is what gets audited. */
class TaskUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID taskId = UUID.randomUUID();
  private SchedulerService scheduler;
  private AuditLog audit;
  private TaskUseCase useCase;

  private TaskDto dto(boolean repetitive) {
    return new TaskDto(taskId, userId, null, null, "Weekly filing", null, ScheduleType.RECURRING,
        "0 0 9 * * MON", null, null, "Asia/Kolkata", null, null, TaskStatus.PENDING, null, null,
        repetitive);
  }

  @BeforeEach
  void setUp() {
    scheduler = mock(SchedulerService.class);
    when(scheduler.get(taskId)).thenReturn(dto(false));
    audit = mock(AuditLog.class);
    useCase = new TaskUseCase(scheduler, audit);
  }

  @Test
  void cancelDelegatesToTheEngineAndAuditsIt() {
    useCase.cancel(userId, taskId);

    verify(scheduler).cancel(taskId);
    verify(audit).record(eq(userId), any(), any(), eq("task.cancelled"), eq("Weekly filing"), any());
  }

  @Test
  void markingRepetitiveAuditsUnderTheRightAction() {
    useCase.markRepetitive(userId, taskId, true);
    verify(audit).record(eq(userId), any(), any(), eq("task.marked_repetitive"), any(), any());

    useCase.markRepetitive(userId, taskId, false);
    verify(audit).record(eq(userId), any(), any(), eq("task.unmarked_repetitive"), any(), any());
  }

  @Test
  void anotherUsersTaskIsReportedMissingAndNothingRuns() {
    UUID somebodyElse = UUID.randomUUID();

    assertThatThrownBy(() -> useCase.cancel(somebodyElse, taskId))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> useCase.markRepetitive(somebodyElse, taskId, true))
        .isInstanceOf(NotFoundException.class);

    verify(scheduler, never()).cancel(any());
    verify(scheduler, never()).markRepetitive(any(), org.mockito.ArgumentMatchers.anyBoolean());
  }
}
