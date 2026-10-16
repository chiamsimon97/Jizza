package com.jizza.platform.usecase.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workflow builder's own save (R31): a workflow the user assembles by hand, as opposed to one
 * an agent proposes.
 *
 * <p>Every workflow the builder saves starts life {@code USER_BUILT}/{@code DRAFT} -- that is the
 * product's decision about what this route means, not something the engine defaults on its
 * behalf. A procedure an agent proposes is {@code AGENT_AUTHORED}/{@code PENDING_REVIEW} and takes
 * a different path entirely, through procedure review; it never touches this class.
 */
@Service
public class WorkflowUseCase {

  private final WorkflowRegistry workflows;
  private final AuditLog audit;

  public WorkflowUseCase(WorkflowRegistry workflows, AuditLog audit) {
    this.workflows = workflows;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<WorkflowDto> list(UUID userId) {
    return workflows.list(userId);
  }

  @Transactional(readOnly = true)
  public WorkflowDto get(UUID userId, UUID workflowId) {
    return ownedBy(userId, workflowId);
  }

  @Transactional(readOnly = true)
  public List<WorkflowDto> versions(UUID userId, UUID workflowId) {
    ownedBy(userId, workflowId);
    return workflows.versions(workflowId);
  }

  @Transactional
  public WorkflowDto save(UUID userId, UUID workflowId, String name, String description,
                          JsonNode graph, JsonNode inputSchema, JsonNode triggerConfig,
                          UUID owningAgentId) {
    boolean creating = workflowId == null;
    if (!creating) {
      WorkflowDto existing = ownedBy(userId, workflowId);
      // The builder saves in place over the current version's snapshot. That is fine for a draft,
      // but on a reviewed workflow it would rewrite the very snapshot the binding check compares
      // against, so anything past draft changes through review instead.
      if (existing.status() != WorkflowStatus.DRAFT) {
        throw new ConflictException("'%s' is %s, so it can't be edited here. Changes to it go through review."
            .formatted(existing.name(), existing.status().name().toLowerCase().replace('_', ' ')));
      }
    }

    WorkflowDto saved = workflows.save(new WorkflowDto(workflowId, userId, name, description,
        graph, inputSchema, triggerConfig, WorkflowOrigin.USER_BUILT, WorkflowStatus.DRAFT,
        owningAgentId, 1, null, null));

    audit.record(userId, ActorType.USER, owningAgentId,
        creating ? "workflow.created" : "workflow.saved", saved.name(),
        Json.obj().put("workflowId", saved.id().toString()).put("version", saved.version()));
    return saved;
  }

  public WorkflowDto ownedBy(UUID userId, UUID workflowId) {
    return workflows.find(workflowId)
        .filter(workflow -> workflow.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
  }
}
