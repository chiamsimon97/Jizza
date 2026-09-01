package com.jizza.platform.usecase.procedure;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.procedure.ProcedureService;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rolling back and disabling a learned procedure (R34-R36) -- the two review actions with no
 * caller but this one.
 *
 * <p>{@code propose}, {@code approve} and {@code reject} stay in {@link ProcedureService} itself:
 * they are also called from inside the engine (an agent's own tool call, and
 * {@code DefaultNotificationBus} answering a review notification), so their policy and audit
 * cannot live only here without breaking those callers. Rollback and disable have no such caller,
 * so the decision to record them in the activity log is this class's to make.
 */
@Service
public class ProcedureReviewUseCase {

  private final ProcedureService procedures;
  private final WorkflowRegistry workflows;
  private final AuditLog audit;

  public ProcedureReviewUseCase(ProcedureService procedures, WorkflowRegistry workflows,
                                AuditLog audit) {
    this.procedures = procedures;
    this.workflows = workflows;
    this.audit = audit;
  }

  @Transactional
  public void rollback(UUID userId, UUID workflowId, int toVersion) {
    ownedBy(userId, workflowId);
    procedures.rollback(workflowId, toVersion);

    WorkflowDto updated = ownedBy(userId, workflowId);
    audit.record(userId, ActorType.USER, null, "procedure.rolled_back", updated.name(),
        Json.obj().put("workflowId", workflowId.toString())
            .put("toVersion", toVersion).put("newVersion", updated.version()));
  }

  @Transactional
  public void disable(UUID userId, UUID workflowId) {
    WorkflowDto before = ownedBy(userId, workflowId);
    procedures.disable(workflowId);

    audit.record(userId, ActorType.USER, null, "procedure.disabled", before.name(), null);
  }

  private WorkflowDto ownedBy(UUID userId, UUID workflowId) {
    return workflows.find(workflowId)
        .filter(workflow -> workflow.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Procedure", workflowId));
  }
}
