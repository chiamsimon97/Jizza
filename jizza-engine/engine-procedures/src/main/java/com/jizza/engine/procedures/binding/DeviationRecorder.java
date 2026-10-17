package com.jizza.engine.procedures.binding;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.ScheduledTaskEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes down a binding deviation so the record outlives the refusal.
 *
 * <p>A separate bean on purpose. The enforcer records a violation and then throws, and a throw
 * rolls back the transaction it happened in -- so the audit entry and the intervention have to be
 * committed by a transaction of their own, or refusing to run a job would leave no trace of why and
 * the user would never be asked what to do instead.
 */
@Component
public class DeviationRecorder {

  private final ObjectProvider<NotificationBus> notifications;
  private final AuditLog audit;

  public DeviationRecorder(ObjectProvider<NotificationBus> notifications, AuditLog audit) {
    this.notifications = notifications;
    this.audit = audit;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(ScheduledTaskEntity task, WorkflowEntity workflow, String message,
                     UUID runId) {
    audit.record(task.getUserId(), ActorType.SYSTEM, task.getAgentId(),
        "procedure.binding_violation", task.getTitle(),
        Json.obj().put("workflowId", workflow == null ? null : workflow.getId().toString())
            .put("reason", message));
    raise(task, workflow, message, runId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordDeviation(ScheduledTaskEntity task, String message, UUID runId) {
    raise(task, null, message, runId);
  }

  private void raise(ScheduledTaskEntity task, WorkflowEntity workflow, String message,
                     UUID runId) {
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
