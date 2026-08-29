package com.jizza.engine.api.scheduler;

import com.jizza.engine.api.dto.TaskDto;
import java.util.List;
import java.util.UUID;

/** Durable one-time and recurring task engine (R29/R30). */
public interface SchedulerService {

  TaskDto schedule(TaskSpec s);

  void cancel(UUID taskId);

  List<TaskDto> list(UUID userId);

  TaskDto get(UUID taskId);

  /** Bind an approved procedure version to a recurring task (R35). */
  void bindProcedure(UUID taskId, UUID workflowId, int version);

  /** Clear the binding, returning the task to free-form agent execution. */
  void unbindProcedure(UUID taskId);

  /** Mark a task as one the user considers repetitive, making it a candidate for learning (R34). */
  void markRepetitive(UUID taskId, boolean repetitive);
}
