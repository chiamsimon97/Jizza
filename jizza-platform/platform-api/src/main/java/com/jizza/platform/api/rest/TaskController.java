package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
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
@AuthenticatedOnly
@RequestMapping("/api/tasks")
public class TaskController {

  private final SchedulerService scheduler;
  private final TaskUseCase tasks;
  private final com.jizza.engine.api.orchestrator.AgentDirectory agents;

  public TaskController(SchedulerService scheduler, TaskUseCase tasks,
                        com.jizza.engine.api.orchestrator.AgentDirectory agents) {
    this.scheduler = scheduler;
    this.tasks = tasks;
    this.agents = agents;
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
    // A one-off time already gone would run the moment it is saved, which is never what the person
    // meant. A minute's grace covers the time spent filling the form in.
    if (request.scheduleType() == com.jizza.engine.api.enums.ScheduleType.ONE_TIME
        && request.runAt() != null
        && request.runAt().isBefore(java.time.Instant.now().minus(java.time.Duration.ofMinutes(1)))) {
      throw new com.jizza.engine.api.error.ValidationException(
          "That time has already passed. Pick a time in the future.");
    }
    // Only one of the caller's own agents can be given the work.
    java.util.UUID userId = CurrentUser.userId();
    agents.byId(request.agentId()).filter(a -> userId.equals(a.userId()))
        .orElseThrow(() -> new com.jizza.engine.api.error.NotFoundException("Agent",
            request.agentId()));
    return scheduler.schedule(new TaskSpec(userId, request.agentId(), request.title(),
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
