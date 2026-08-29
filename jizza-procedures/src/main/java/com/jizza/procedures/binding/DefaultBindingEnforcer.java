package com.jizza.procedures.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.audit.AuditLog;
import com.jizza.common.enums.ActorType;
import com.jizza.common.enums.NotificationKind;
import com.jizza.common.enums.WorkflowStatus;
import com.jizza.common.error.JizzaException;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.notification.NotificationBus;
import com.jizza.common.notification.NotificationContext;
import com.jizza.common.procedure.BindingEnforcer;
import com.jizza.common.util.Json;
import com.jizza.common.workflow.graph.WorkflowGraph;
import com.jizza.persistence.entity.ScheduledTaskEntity;
import com.jizza.persistence.entity.WorkflowEntity;
import com.jizza.persistence.entity.WorkflowVersionEntity;
import com.jizza.persistence.repository.ScheduledTaskRepository;
import com.jizza.persistence.repository.WorkflowRepository;
import com.jizza.persistence.repository.WorkflowVersionRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes "bound means bound" real (R36).
 *
 * <p>Before a bound job runs, three things are checked against persisted state: the task really is
 * bound to this workflow and version, that version is approved and enabled, and the graph about to
 * execute still hashes to what was reviewed. A mismatch raises an intervention and refuses the run
 * rather than executing something the user never saw.
 */
@Component
public class DefaultBindingEnforcer implements BindingEnforcer {

  private static final Logger log = LoggerFactory.getLogger(DefaultBindingEnforcer.class);

  private final ScheduledTaskRepository tasks;
  private final WorkflowRepository workflows;
  private final WorkflowVersionRepository versions;
  private final ObjectProvider<NotificationBus> notifications;
  private final AuditLog audit;

  public DefaultBindingEnforcer(ScheduledTaskRepository tasks, WorkflowRepository workflows,
                                WorkflowVersionRepository versions,
                                ObjectProvider<NotificationBus> notifications, AuditLog audit) {
    this.tasks = tasks;
    this.workflows = workflows;
    this.versions = versions;
    this.notifications = notifications;
    this.audit = audit;
  }

  @Override
  @Transactional
  public void assertBound(UUID taskId, UUID workflowId, int version, JsonNode graph) {
    ScheduledTaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task", taskId));
    WorkflowEntity workflow = workflows.findById(workflowId)
        .orElseThrow(() -> new NotFoundException("Procedure", workflowId));

    if (!workflowId.equals(task.getBoundWorkflowId())
        || task.getBoundVersion() == null || task.getBoundVersion() != version) {
      throw deviation(task, workflow, "This job is not bound to that procedure version");
    }
    if (workflow.isDisabled()) {
      throw deviation(task, workflow, "The bound procedure is disabled");
    }
    if (workflow.getStatus() != WorkflowStatus.APPROVED) {
      throw deviation(task, workflow, "The bound procedure is " + workflow.getStatus()
          + " rather than approved");
    }

    WorkflowVersionEntity snapshot = versions.findByWorkflowIdAndVersion(workflowId, version)
        .orElseThrow(() -> deviation(task, workflow,
            "Version " + version + " of the bound procedure no longer exists"));

    // The fingerprint is what proves the graph is byte-for-byte the reviewed one.
    String approved = snapshot.getGraphFingerprint();
    String actual = graph != null
        ? WorkflowGraph.from(graph).fingerprint()
        : WorkflowGraph.from(snapshot.getGraph()).fingerprint();
    if (approved != null && !approved.equals(actual)) {
      throw deviation(task, workflow,
          "The procedure about to run does not match the version that was approved");
    }
  }

  @Override
  @Transactional
  public void reportDeviation(UUID runId, UUID taskId, String detail) {
    tasks.findById(taskId).ifPresent(task -> raise(task, null, detail, runId));
  }

  private JizzaException deviation(ScheduledTaskEntity task, WorkflowEntity workflow,
                                   String message) {
    log.warn("Refusing to run task {}: {}", task.getTitle(), message);
    audit.record(task.getUserId(), ActorType.SYSTEM, task.getAgentId(),
        "procedure.binding_violation", task.getTitle(),
        Json.obj().put("workflowId", String.valueOf(workflow == null ? null : workflow.getId()))
            .put("reason", message));
    raise(task, workflow, message, null);
    return new JizzaException(HttpStatus.CONFLICT, "binding-violation",
        "Bound procedure mismatch", message);
  }

  private void raise(ScheduledTaskEntity task, WorkflowEntity workflow, String message, UUID runId) {
    NotificationBus bus = notifications.getIfAvailable();
    if (bus == null) return;
    bus.raise(NotificationKind.INSTRUCTION_REQUIRED, task.getAgentId(),
        new NotificationContext(task.getUserId(),
            "'" + task.getTitle() + "' did not run: " + message
                + " How would you like to proceed?",
            Json.obj().put("taskId", task.getId().toString())
                .put("workflowId", workflow == null ? null : workflow.getId().toString()),
            task.getId(), workflow == null ? null : workflow.getId(), runId, null));
  }
}
