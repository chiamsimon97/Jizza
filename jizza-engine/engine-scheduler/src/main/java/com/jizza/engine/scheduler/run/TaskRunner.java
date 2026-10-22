package com.jizza.engine.scheduler.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.enums.ScheduleType;
import com.jizza.engine.api.enums.TaskStatus;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.error.AutonomyDeniedException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import com.jizza.engine.persistence.entity.ScheduledTaskEntity;
import com.jizza.engine.persistence.repository.ScheduledTaskRepository;
import com.jizza.engine.scheduler.config.SchedulerProperties;
import com.jizza.engine.scheduler.recurrence.RecurrenceCalculator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs one due task (R29).
 *
 * <p>The branch that matters: a task with a bound procedure runs <em>that approved workflow</em>
 * and nothing else. Only an unbound task gets to put its instruction through the owning agent, which
 * is what "the agent follows the procedure exactly rather than improvising" means in practice (R36).
 */
@Component
public class TaskRunner {

  private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

  private final ScheduledTaskRepository tasks;
  private final Agent baseAgent;
  private final ObjectProvider<WorkflowEngine> workflowEngine;
  private final RecurrenceCalculator recurrence;
  private final KillSwitch killSwitch;
  private final AutonomyGate autonomyGate;
  private final AuditLog audit;
  private final SchedulerProperties props;
  private final ObjectProvider<com.jizza.engine.api.procedure.BindingEnforcer> bindingEnforcer;
  private final ApplicationEventPublisher events;
  private ObjectProvider<WorkflowLibrary> library;
  private ObjectProvider<NotificationBus> notifications;

  public TaskRunner(ScheduledTaskRepository tasks, Agent baseAgent,
                    ObjectProvider<WorkflowEngine> workflowEngine, RecurrenceCalculator recurrence,
                    KillSwitch killSwitch, AutonomyGate autonomyGate, AuditLog audit,
                    SchedulerProperties props,
                    ObjectProvider<com.jizza.engine.api.procedure.BindingEnforcer> bindingEnforcer,
                    ApplicationEventPublisher events) {
    this.tasks = tasks;
    this.baseAgent = baseAgent;
    this.workflowEngine = workflowEngine;
    this.recurrence = recurrence;
    this.killSwitch = killSwitch;
    this.autonomyGate = autonomyGate;
    this.audit = audit;
    this.props = props;
    this.bindingEnforcer = bindingEnforcer;
    this.events = events;
  }

  /** Optional: without the library every unbound task goes to its agent. */
  @Autowired(required = false)
  public void setLibrary(ObjectProvider<WorkflowLibrary> library,
                         ObjectProvider<NotificationBus> notifications) {
    this.library = library;
    this.notifications = notifications;
  }

  /**
   * Claims a task for this worker. Runs in its own transaction so the claim commits before the work
   * starts: combined with the row's optimistic-lock version, that is what stops a second worker
   * picking up the same task.
   *
   * @return true when this worker owns the run
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claim(UUID taskId) {
    var maybe = tasks.findById(taskId);
    if (maybe.isEmpty()) return false;
    ScheduledTaskEntity task = maybe.get();
    if (task.getStatus() != TaskStatus.PENDING || task.getNextRunAt() == null
        || task.getNextRunAt().isAfter(Instant.now())) {
      return false;
    }
    task.setStatus(TaskStatus.RUNNING);
    tasks.save(task);
    return true;
  }

  @Transactional
  public void run(UUID taskId) {
    ScheduledTaskEntity task = tasks.findById(taskId).orElse(null);
    if (task == null) return;

    Instant due = task.getNextRunAt() == null ? Instant.now() : task.getNextRunAt();
    try {
      // The kill switch and the per-agent toggle are checked here, immediately before the work,
      // not only when the task was scheduled.
      killSwitch.requireActive(task.getUserId());
      if (!autonomyGate.agentEnabled(task.getAgentId())) {
        throw new AutonomyDeniedException("The agent that owns this task is disabled");
      }

      String outcome = task.getBoundWorkflowId() != null ? runBoundProcedure(task) : null;
      boolean reused = false;
      if (outcome == null) {
        outcome = runFromLibrary(task);
        reused = outcome != null;
      }
      if (outcome == null) outcome = runAgent(task);
      finish(task, due, TaskStatus.DONE, outcome);

      // A repetitive job with nothing bound yet is the trigger for learning a procedure. The
      // scheduler only announces it; deciding what to do about it belongs to jizza-procedures.
      // A job a saved workflow just did already has its procedure.
      if (task.isRepetitive() && task.getBoundWorkflowId() == null && !reused) {
        events.publishEvent(new com.jizza.engine.api.event.RepetitiveTaskCompleted(task.getUserId(),
            task.getAgentId(), task.getId(), task.getTitle(), outcome));
      }
    } catch (AutonomyDeniedException e) {
      log.info("Task {} did not run: {}", task.getTitle(), e.getMessage());
      // Not a failure: autonomy is paused or the agent is off. Leave the schedule intact so the
      // task simply resumes when autonomy comes back.
      finish(task, due, TaskStatus.PENDING, "Skipped: " + e.getMessage());
      audit.record(task.getUserId(), ActorType.SYSTEM, task.getAgentId(), "task.skipped",
          task.getTitle(), Json.obj().put("reason", e.getMessage()));
    } catch (RuntimeException e) {
      log.warn("Task {} failed", task.getTitle(), e);
      finish(task, due, TaskStatus.FAILED, "Failed: " + e.getMessage());
      audit.record(task.getUserId(), ActorType.SYSTEM, task.getAgentId(), "task.failed",
          task.getTitle(), Json.obj().put("error", String.valueOf(e.getMessage())));
    }
  }

  private String runBoundProcedure(ScheduledTaskEntity task) {
    WorkflowEngine engine = workflowEngine.getIfAvailable();
    if (engine == null) {
      throw new IllegalStateException("This task is bound to a procedure but the workflow engine "
          + "is not available");
    }
    // A bound job must run the approved graph exactly; anything else raises an intervention
    // rather than being quietly improvised (R36).
    var enforcer = bindingEnforcer.getIfAvailable();
    if (enforcer != null) {
      enforcer.assertBound(task.getId(), task.getBoundWorkflowId(), task.getBoundVersion(), null);
    }
    var run = engine.start(task.getBoundWorkflowId(), task.getBoundVersion(),
        task.getPayload(), "scheduled_task:" + task.getId());
    audit.record(task.getUserId(), ActorType.SYSTEM, task.getAgentId(), "task.ran_procedure",
        task.getTitle(), Json.obj().put("runId", run.id().toString())
            .put("workflowId", task.getBoundWorkflowId().toString())
            .put("version", task.getBoundVersion()));
    return "Ran procedure version " + task.getBoundVersion() + " (run " + run.id() + ", "
        + run.status() + ")";
  }

  /**
   * Runs the user's saved workflow for this job, when one clearly does it and the user allowed it
   * to run on a schedule, and tells them the first time it does. Null when there is none, or when
   * its guardrails stop it today, so the job goes to the agent as it always has.
   */
  private String runFromLibrary(ScheduledTaskEntity task) {
    WorkflowLibrary lib = library == null ? null : library.getIfAvailable();
    if (lib == null) return null;
    String instruction = instruction(task);
    List<WorkflowLibrary.Match> found;
    try {
      found = lib.find(task.getUserId(), instruction, WorkflowLibrary.Use.SCHEDULE, 1);
    } catch (RuntimeException e) {
      log.info("Could not look up saved workflows for task {}: {}", task.getTitle(), e.getMessage());
      return null;
    }
    if (found.isEmpty()) return null;
    WorkflowLibrary.Match match = found.get(0);
    JsonNode input = task.getPayload() != null && task.getPayload().path("input").isObject()
        ? task.getPayload().path("input") : Json.obj();
    RunDto run;
    try {
      run = lib.reuse(task.getUserId(), match.workflowId(), input, WorkflowLibrary.Use.SCHEDULE,
          "scheduled_task:" + task.getId(), false);
    } catch (ValidationException e) {
      log.info("Task {} did not reuse '{}': {}", task.getTitle(), match.name(), e.getMessage());
      return null;
    }
    tell(task, match, run);
    return "Ran your saved workflow '" + match.name() + "' (run " + run.id() + ", "
        + run.status() + ")";
  }

  /** Once a week at most per task and workflow, so a daily job does not fill the inbox. */
  private void tell(ScheduledTaskEntity task, WorkflowLibrary.Match match, RunDto run) {
    NotificationBus bus = notifications == null ? null : notifications.getIfAvailable();
    if (bus == null) return;
    String body = "'" + task.getTitle() + "' ran your saved workflow '" + match.name()
        + "' instead of working the job out again.";
    try {
      if (bus.raisedSince(task.getUserId(), task.getAgentId(), NotificationKind.INFO, body,
          Instant.now().minus(java.time.Duration.ofDays(7)))) {
        return;
      }
      bus.raise(NotificationKind.INFO, task.getAgentId(), NotificationContext.forRun(task.getUserId(),
          body, Json.obj().put("workflowId", match.workflowId().toString())
              .put("taskId", task.getId().toString()), run.id()));
    } catch (RuntimeException e) {
      log.info("Could not tell the user task {} reused '{}': {}", task.getTitle(), match.name(),
          e.getMessage());
    }
  }

  private static String instruction(ScheduledTaskEntity task) {
    return task.getPayload() == null
        ? task.getTitle()
        : task.getPayload().path("instruction").asText(task.getTitle());
  }

  private String runAgent(ScheduledTaskEntity task) {
    String instruction = instruction(task);

    AgentResult result = baseAgent.run(new AgentRequest(task.getUserId(), task.getAgentId(),
        instruction, task.getPayload(), java.util.List.of(), ModelTier.STANDARD, true,
        com.jizza.engine.api.cosmos.AccessContext.task(task.getId()).toTrace())
        .autonomous(true));

    audit.record(task.getUserId(), ActorType.AGENT, task.getAgentId(), "task.ran", task.getTitle(),
        Json.obj().put("truncated", result.truncatedByBudget()));
    return result.ok() ? abbreviate(result.text()) : "Failed: " + result.error();
  }

  /** Records the outcome and computes the next occurrence, or retires a one-time task. */
  private void finish(ScheduledTaskEntity task, Instant due, TaskStatus status, String outcome) {
    task.setLastRunAt(Instant.now());
    task.setLastResult(abbreviate(outcome));

    if (task.getScheduleType() == ScheduleType.ONE_TIME) {
      task.setStatus(status == TaskStatus.PENDING ? TaskStatus.PENDING : status);
      // A one-time task that actually ran has no next occurrence; one that was skipped keeps its
      // slot so it can still fire once autonomy resumes.
      task.setNextRunAt(status == TaskStatus.PENDING ? due : null);
    } else {
      task.setStatus(TaskStatus.PENDING);
      task.setNextRunAt(recurrence.nextAfter(task.getCronOrRrule(), task.getTimezone(),
          task.getCreatedAt(), Instant.now(), 0).orElse(null));
      if (task.getNextRunAt() == null) task.setStatus(TaskStatus.DONE);
    }
    tasks.save(task);
  }

  private String abbreviate(String text) {
    if (text == null) return null;
    return text.length() <= 1000 ? text : text.substring(0, 1000) + "...";
  }
}
