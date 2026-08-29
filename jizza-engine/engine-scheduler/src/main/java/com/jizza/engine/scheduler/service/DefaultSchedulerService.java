package com.jizza.engine.scheduler.service;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.TaskDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.CatchUpPolicy;
import com.jizza.engine.api.enums.ScheduleType;
import com.jizza.engine.api.enums.TaskStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.scheduler.SchedulerService;
import com.jizza.engine.api.scheduler.TaskSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.ScheduledTaskEntity;
import com.jizza.engine.persistence.repository.ScheduledTaskRepository;
import com.jizza.engine.scheduler.config.SchedulerProperties;
import com.jizza.engine.scheduler.recurrence.RecurrenceCalculator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Task lifecycle: create, cancel, list, bind a procedure, mark repetitive (R29, R34-R36). */
@Service
public class DefaultSchedulerService implements SchedulerService {

  private final ScheduledTaskRepository tasks;
  private final RecurrenceCalculator recurrence;
  private final AgentDirectory agents;
  private final AuditLog audit;
  private final SchedulerProperties props;

  public DefaultSchedulerService(ScheduledTaskRepository tasks, RecurrenceCalculator recurrence,
                                 AgentDirectory agents, AuditLog audit, SchedulerProperties props) {
    this.tasks = tasks;
    this.recurrence = recurrence;
    this.agents = agents;
    this.audit = audit;
    this.props = props;
  }

  @Override
  @Transactional
  public TaskDto schedule(TaskSpec spec) {
    if (spec.title() == null || spec.title().isBlank()) {
      throw new ValidationException("A task needs a title");
    }
    String timezone = spec.timezone() == null || spec.timezone().isBlank()
        ? props.getDefaultTimezone() : spec.timezone();

    ScheduledTaskEntity entity = new ScheduledTaskEntity();
    entity.setUserId(spec.userId());
    entity.setAgentId(spec.agentId());
    entity.setTitle(spec.title());
    entity.setPayload(spec.payload());
    entity.setScheduleType(spec.scheduleType());
    entity.setTimezone(timezone);
    entity.setStatus(TaskStatus.PENDING);
    entity.setRepetitive(spec.repetitive());
    entity.setCatchUpPolicy(props.getDefaultCatchUpPolicy());
    entity.setCreatedAt(Instant.now());

    if (spec.scheduleType() == ScheduleType.ONE_TIME) {
      if (spec.runAt() == null) throw new ValidationException("A one-time task needs a run time");
      entity.setRunAt(spec.runAt());
      entity.setNextRunAt(spec.runAt());
    } else {
      recurrence.validate(spec.cronOrRrule(), timezone);
      entity.setCronOrRrule(spec.cronOrRrule());
      entity.setNextRunAt(recurrence
          .nextAfter(spec.cronOrRrule(), timezone, entity.getCreatedAt(), Instant.now(), 0)
          .orElseThrow(() -> new ValidationException("That schedule has no future occurrences")));
    }

    tasks.save(entity);
    audit.record(spec.userId(), ActorType.USER, spec.agentId(), "task.scheduled", spec.title(),
        Json.obj().put("taskId", entity.getId().toString())
            .put("nextRunAt", String.valueOf(entity.getNextRunAt())));
    return toDto(entity);
  }

  @Override
  @Transactional
  public void cancel(UUID taskId) {
    ScheduledTaskEntity entity = require(taskId);
    entity.setStatus(TaskStatus.CANCELLED);
    entity.setNextRunAt(null);
    tasks.save(entity);
    audit.record(entity.getUserId(), ActorType.USER, null, "task.cancelled", entity.getTitle(), null);
  }

  @Override
  @Transactional(readOnly = true)
  public List<TaskDto> list(UUID userId) {
    return tasks.findByUserIdOrderByNextRunAtAsc(userId).stream().map(this::toDto).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public TaskDto get(UUID taskId) {
    return toDto(require(taskId));
  }

  @Override
  @Transactional
  public void bindProcedure(UUID taskId, UUID workflowId, int version) {
    ScheduledTaskEntity entity = require(taskId);
    entity.setBoundWorkflowId(workflowId);
    entity.setBoundVersion(version);
    tasks.save(entity);
    audit.record(entity.getUserId(), ActorType.USER, null, "task.procedure_bound",
        entity.getTitle(),
        Json.obj().put("workflowId", workflowId.toString()).put("version", version));
  }

  @Override
  @Transactional
  public void unbindProcedure(UUID taskId) {
    ScheduledTaskEntity entity = require(taskId);
    entity.setBoundWorkflowId(null);
    entity.setBoundVersion(null);
    tasks.save(entity);
    audit.record(entity.getUserId(), ActorType.USER, null, "task.procedure_unbound",
        entity.getTitle(), null);
  }

  @Override
  @Transactional
  public void markRepetitive(UUID taskId, boolean repetitive) {
    ScheduledTaskEntity entity = require(taskId);
    entity.setRepetitive(repetitive);
    tasks.save(entity);
    audit.record(entity.getUserId(), ActorType.USER, null,
        repetitive ? "task.marked_repetitive" : "task.unmarked_repetitive", entity.getTitle(), null);
  }

  @Transactional
  public void setCatchUpPolicy(UUID taskId, CatchUpPolicy policy) {
    ScheduledTaskEntity entity = require(taskId);
    entity.setCatchUpPolicy(policy);
    tasks.save(entity);
  }

  private ScheduledTaskEntity require(UUID taskId) {
    return tasks.findById(taskId).orElseThrow(() -> new NotFoundException("Task", taskId));
  }

  TaskDto toDto(ScheduledTaskEntity t) {
    String agentName = agents.byId(t.getAgentId())
        .map(com.jizza.engine.api.dto.AgentDto::name).orElse(null);
    return new TaskDto(t.getId(), t.getUserId(), t.getAgentId(), agentName, t.getTitle(),
        t.getPayload(), t.getScheduleType(), t.getCronOrRrule(), t.getRunAt(), t.getNextRunAt(),
        t.getTimezone(), t.getBoundWorkflowId(), t.getBoundVersion(), t.getStatus(),
        t.getLastRunAt(), t.getLastResult(), t.isRepetitive());
  }
}
