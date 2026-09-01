package com.jizza.platform.usecase.task;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.TaskDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.scheduler.SchedulerService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancelling a task and marking one repetitive (R29, R34) -- the two task actions with no caller
 * but this one.
 *
 * <p>{@code schedule} (creating a task) and {@code unbindProcedure} stay behind
 * {@link SchedulerService} itself: {@code schedule} is also called from
 * {@code ScheduleTaskToolProvider} mid an agent's own tool call, and {@code unbindProcedure} is
 * also called from {@code DefaultProcedureService} when a procedure is rejected, revised or
 * disabled -- both purely engine-internal callers this use-case cannot reach. Cancel and
 * mark-repetitive have no such caller, so the decision to record them in the activity log is this
 * class's to make.
 */
@Service
public class TaskUseCase {

  private final SchedulerService scheduler;
  private final AuditLog audit;

  public TaskUseCase(SchedulerService scheduler, AuditLog audit) {
    this.scheduler = scheduler;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<TaskDto> list(UUID userId) {
    return scheduler.list(userId);
  }

  @Transactional(readOnly = true)
  public TaskDto get(UUID userId, UUID taskId) {
    return ownedBy(userId, taskId);
  }

  @Transactional
  public void cancel(UUID userId, UUID taskId) {
    TaskDto task = ownedBy(userId, taskId);
    scheduler.cancel(taskId);
    audit.record(userId, ActorType.USER, null, "task.cancelled", task.title(), null);
  }

  @Transactional
  public TaskDto markRepetitive(UUID userId, UUID taskId, boolean repetitive) {
    TaskDto task = ownedBy(userId, taskId);
    scheduler.markRepetitive(taskId, repetitive);
    audit.record(userId, ActorType.USER, null,
        repetitive ? "task.marked_repetitive" : "task.unmarked_repetitive", task.title(), null);
    return scheduler.get(taskId);
  }

  public TaskDto ownedBy(UUID userId, UUID taskId) {
    TaskDto task = scheduler.get(taskId);
    if (!task.userId().equals(userId)) {
      throw new NotFoundException("Task", taskId);
    }
    return task;
  }
}
