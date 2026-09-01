package com.jizza.platform.api.rest;

import com.jizza.engine.api.dto.TaskDto;
import com.jizza.engine.api.scheduler.SchedulerService;
import com.jizza.engine.api.scheduler.TaskSpec;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.task.TaskUseCase;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One-time and recurring tasks, including marking one repetitive so it can be learned (R29/R34). */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

  private final SchedulerService scheduler;
  private final TaskUseCase tasks;

  public TaskController(SchedulerService scheduler, TaskUseCase tasks) {
    this.scheduler = scheduler;
    this.tasks = tasks;
  }

  @GetMapping
  public List<TaskDto> list() {
    return tasks.list(CurrentUser.userId());
  }

  @GetMapping("/{id}")
  public TaskDto get(@PathVariable UUID id) {
    return owned(id);
  }

  @PostMapping
  public TaskDto create(@Valid @RequestBody Requests.Task request) {
    return scheduler.schedule(new TaskSpec(CurrentUser.userId(), request.agentId(), request.title(),
        request.payload(), request.scheduleType(), request.cronOrRrule(), request.runAt(),
        request.timezone(), Boolean.TRUE.equals(request.repetitive())));
  }

  @DeleteMapping("/{id}")
  public Responses.Message cancel(@PathVariable UUID id) {
    tasks.cancel(CurrentUser.userId(), id);
    return new Responses.Message("Task cancelled.");
  }

  /**
   * Marking a job repetitive is what invites the owning agent to propose a procedure for it. Per
   * the Charter this is the user's call, never the agent's.
   */
  @PostMapping("/{id}/repetitive")
  public TaskDto markRepetitive(@PathVariable UUID id,
                                @Valid @RequestBody Requests.TaskRepetitive request) {
    return tasks.markRepetitive(CurrentUser.userId(), id, request.repetitive());
  }

  @PostMapping("/{id}/unbind")
  @com.jizza.engine.api.util.StepUpRequired
  public TaskDto unbind(@PathVariable UUID id) {
    owned(id);
    scheduler.unbindProcedure(id);
    return scheduler.get(id);
  }

  private TaskDto owned(UUID taskId) {
    return tasks.ownedBy(CurrentUser.userId(), taskId);
  }
}
