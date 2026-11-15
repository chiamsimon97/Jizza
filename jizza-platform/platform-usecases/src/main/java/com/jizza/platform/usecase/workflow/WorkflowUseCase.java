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
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
  private WorkflowLibrary library;
  private com.jizza.engine.api.workflow.WorkflowEngine engine;

  public WorkflowUseCase(WorkflowRegistry workflows, AuditLog audit) {
    this.workflows = workflows;
    this.audit = audit;
  }

  @Autowired(required = false)
  public void setLibrary(WorkflowLibrary library) {
    this.library = library;
  }

  @Autowired(required = false)
  public void setEngine(com.jizza.engine.api.workflow.WorkflowEngine engine) {
    this.engine = engine;
  }

  /**
   * Deletes a workflow (a saved recording is one too): its unfinished runs are cancelled, it stops
   * starting from its form or schedule, and it leaves every list. Its run history stays. Not one
   * transaction: each run is cancelled on its own, so one that ended meanwhile spoils nothing.
   */
  public void delete(UUID userId, UUID workflowId) {
    WorkflowDto workflow = ownedBy(userId, workflowId);
    List<UUID> open = workflows.unfinishedRuns(workflowId);
    if (engine != null) {
      for (UUID runId : open) {
        try {
          engine.cancel(runId);
        } catch (RuntimeException ended) {
          // It ended on its own meanwhile.
        }
      }
    }
    workflows.delete(workflowId);
    audit.record(userId, ActorType.USER, null, "workflow.deleted", workflow.name(),
        Json.obj().put("workflowId", workflowId.toString()).put("cancelledRuns", open.size()));
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
    return save(userId, workflowId, name, description, graph, inputSchema, triggerConfig,
        owningAgentId, null);
  }

  /**
   * As above, also keeping it in the workflow library when {@code reuse} is given: what the user
   * approved on a plan Jizza offered to save, guardrails included.
   */
  @Transactional
  public WorkflowDto save(UUID userId, UUID workflowId, String name, String description,
                          JsonNode graph, JsonNode inputSchema, JsonNode triggerConfig,
                          UUID owningAgentId, ReuseProfile reuse) {
    return save(userId, workflowId, name, description, graph, inputSchema, triggerConfig,
        owningAgentId, reuse, null);
  }

  /**
   * As above. {@code baseVersion} is the version the builder opened: when the workflow has had a
   * newer version saved since (an edited recording, an applied fix), saving would write the old
   * steps over it, so it is refused.
   */
  @Transactional
  public WorkflowDto save(UUID userId, UUID workflowId, String name, String description,
                          JsonNode graph, JsonNode inputSchema, JsonNode triggerConfig,
                          UUID owningAgentId, ReuseProfile reuse, Integer baseVersion) {
    boolean creating = workflowId == null;
    if (!creating) {
      WorkflowDto existing = ownedBy(userId, workflowId);
      if (baseVersion != null && existing.version() != baseVersion) {
        throw new ConflictException(("'%s' has a newer version (%d) than the one open here (%d), "
            + "so saving would undo it. Close it and open it again to see the newest steps.")
            .formatted(existing.name(), existing.version(), baseVersion));
      }
      // The builder saves in place over the current version's snapshot. That is fine for a draft,
      // but on a reviewed workflow it would rewrite the very snapshot the binding check compares
      // against, so anything past draft changes through review instead.
      if (existing.status() != WorkflowStatus.DRAFT) {
        throw new ConflictException("'%s' is %s, so it can't be edited here. Changes to it go through review."
            .formatted(existing.name(), existing.status().name().toLowerCase().replace('_', ' ')));
      }
    }

    // A workflow the person turned on stays on when they change its steps here themselves.
    boolean wasOn = !creating && ownedBy(userId, workflowId).enabled();
    WorkflowDto saved = workflows.save(new WorkflowDto(workflowId, userId, name, description,
        graph, inputSchema, triggerConfig, WorkflowOrigin.USER_BUILT, WorkflowStatus.DRAFT,
        owningAgentId, 1, null, null));

    audit.record(userId, ActorType.USER, owningAgentId,
        creating ? "workflow.created" : "workflow.saved", saved.name(),
        Json.obj().put("workflowId", saved.id().toString()).put("version", saved.version()));
    if (wasOn && !saved.enabled()) saved = workflows.setEnabled(saved.id(), true);
    if (reuse != null && library != null) {
      library.describe(userId, saved.id(), reuse);
      return ownedBy(userId, saved.id());
    }
    return saved;
  }

  /** Turns a workflow on or off for its triggers (a form, a schedule). */
  @Transactional
  public WorkflowDto setEnabled(UUID userId, UUID workflowId, boolean enabled) {
    WorkflowDto workflow = ownedBy(userId, workflowId);
    WorkflowDto saved = workflows.setEnabled(workflowId, enabled);
    audit.record(userId, ActorType.USER, null, enabled ? "workflow.enabled" : "workflow.disabled",
        workflow.name(), Json.obj().put("workflowId", workflowId.toString()));
    return saved;
  }

  /** Makes the form's submissions start the workflow; null for none. The caller checks the form is theirs. */
  @Transactional
  public WorkflowDto setFormTrigger(UUID userId, UUID workflowId, UUID formId) {
    ownedBy(userId, workflowId);
    return workflows.setFormTrigger(workflowId, formId);
  }

  /** What the library keeps for reusing a workflow, as the user set it; null takes it out. */
  @Transactional
  public WorkflowLibrary.Match describe(UUID userId, UUID workflowId, ReuseProfile reuse) {
    ownedBy(userId, workflowId);
    return requireLibrary().describe(userId, workflowId, reuse);
  }

  @Transactional(readOnly = true)
  public List<WorkflowLibrary.Match> library(UUID userId) {
    return requireLibrary().list(userId);
  }

  /** Every workflow the user can run from a chat, newest first, with the inputs each takes. */
  @Transactional(readOnly = true)
  public List<WorkflowLibrary.Match> runnable(UUID userId) {
    return requireLibrary().runnable(userId);
  }

  private WorkflowLibrary requireLibrary() {
    if (library == null) throw new IllegalStateException("The workflow library is not available");
    return library;
  }

  public WorkflowDto ownedBy(UUID userId, UUID workflowId) {
    return workflows.find(workflowId)
        .filter(workflow -> workflow.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
  }
}
