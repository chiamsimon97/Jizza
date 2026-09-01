package com.jizza.engine.procedures.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.procedure.ProcedureService;
import com.jizza.engine.api.scheduler.SchedulerService;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowVersionEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.ScheduledTaskRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowVersionRepository;
import com.jizza.engine.workflow.engine.WorkflowValidator;
import com.jizza.engine.workflow.registry.DefaultSkillStore;
import com.jizza.engine.workflow.registry.DefaultWorkflowRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learned procedures: propose, review, approve-and-bind, revise, roll back (R34-R36).
 *
 * <p>The rule that gives the feature its point: an agent-authored workflow is inert until a human
 * approves it, and any subsequent edit drops it back to {@code pending_review} as a new version.
 * There is no path here that makes a graph runnable without someone having looked at it.
 *
 * <p>Most of this stays engine-side rather than moving to a platform use-case, unlike the other
 * domains split out so far, because {@code propose} is called from {@code ProposeWorkflowToolProvider}
 * mid agent-turn and {@code approve}/{@code reject} are called from
 * {@code DefaultNotificationBus#answerReview} when a review notification is answered -- both
 * purely engine-internal callers that cannot reach across to platform code. {@code revise} shares
 * its notifying, auditing {@code submitForReview} helper with {@code propose} for the same reason.
 * Only {@code rollback} and {@code disable} have no caller but the platform's
 * {@code ProcedureController}, so only their audit logging moved out, to
 * {@code ProcedureReviewUseCase}.
 */
@Service
public class DefaultProcedureService implements ProcedureService {

  private static final Logger log = LoggerFactory.getLogger(DefaultProcedureService.class);

  private final WorkflowRepository workflows;
  private final WorkflowVersionRepository versions;
  private final ScheduledTaskRepository tasks;
  private final AgentRepository agents;
  private final DefaultWorkflowRegistry registry;
  private final DefaultSkillStore skills;
  private final WorkflowValidator validator;
  private final SchedulerService scheduler;
  private final ObjectProvider<NotificationBus> notifications;
  private final AuditLog audit;

  public DefaultProcedureService(WorkflowRepository workflows, WorkflowVersionRepository versions,
                                 ScheduledTaskRepository tasks, AgentRepository agents,
                                 DefaultWorkflowRegistry registry, DefaultSkillStore skills,
                                 WorkflowValidator validator,
                                 SchedulerService scheduler,
                                 ObjectProvider<NotificationBus> notifications, AuditLog audit) {
    this.workflows = workflows;
    this.versions = versions;
    this.tasks = tasks;
    this.agents = agents;
    this.registry = registry;
    this.skills = skills;
    this.validator = validator;
    this.scheduler = scheduler;
    this.notifications = notifications;
    this.audit = audit;
  }

  @Override
  @Transactional
  public WorkflowDto propose(UUID agentId, UUID sourceTaskId, String name, String description,
                             JsonNode graphJson) {
    AgentEntity agent = agents.findById(agentId)
        .orElseThrow(() -> new NotFoundException("Agent", agentId));

    WorkflowGraph graph = WorkflowGraph.from(graphJson);
    validator.validate(graph);

    // Re-proposing for the same task revises the existing proposal rather than piling up rival
    // drafts the user has to choose between.
    Optional<WorkflowEntity> existing = sourceTaskId == null ? Optional.empty()
        : workflows.findBySourceTaskId(sourceTaskId).stream()
            .filter(w -> w.getOrigin() == WorkflowOrigin.AGENT_AUTHORED)
            .findFirst();
    if (existing.isPresent()) {
      return submitForReview(existing.get(), graph, agent,
          "%s has revised the procedure for '%s' and it needs reviewing again."
              .formatted(agent.getName(), existing.get().getName()));
    }

    WorkflowEntity entity = new WorkflowEntity();
    entity.setUserId(agent.getUserId());
    entity.setName(name == null || name.isBlank() ? "Procedure from a repeated job" : name);
    entity.setDescription(description);
    entity.setGraph(graph.toJson());
    entity.setGraphFingerprint(graph.fingerprint());
    entity.setOrigin(WorkflowOrigin.AGENT_AUTHORED);
    entity.setStatus(WorkflowStatus.PENDING_REVIEW);
    entity.setOwningAgentId(agentId);
    entity.setSourceTaskId(sourceTaskId);
    entity.setVersion(1);
    workflows.save(entity);
    registry.snapshot(entity);

    audit.record(agent.getUserId(), ActorType.AGENT, agentId, "procedure.proposed",
        entity.getName(), Json.obj().put("workflowId", entity.getId().toString())
            .put("sourceTaskId", String.valueOf(sourceTaskId)));
    raiseReview(entity, agent, ("%s has drafted a procedure for '%s' and needs your review "
        + "before using it.").formatted(agent.getName(), entity.getName()));
    return DefaultWorkflowRegistry.toDto(entity);
  }

  @Override
  @Transactional
  public void approve(UUID workflowId) {
    WorkflowEntity workflow = require(workflowId);
    if (workflow.getStatus() == WorkflowStatus.APPROVED) return;

    workflow.setStatus(WorkflowStatus.APPROVED);
    workflow.setReviewFeedback(null);
    workflow.setDisabled(false);
    workflows.save(workflow);
    registry.snapshot(workflow);
    skills.refreshInvocability(workflowId);

    // Approval is also what binds the procedure to the job that produced it, so the next run
    // follows the reviewed graph rather than free-forming again (R35).
    if (workflow.getSourceTaskId() != null) {
      tasks.findById(workflow.getSourceTaskId()).ifPresent(task ->
          scheduler.bindProcedure(task.getId(), workflowId, workflow.getVersion()));
    }
    audit.record(workflow.getUserId(), ActorType.USER, null, "procedure.approved",
        workflow.getName(), Json.obj().put("workflowId", workflowId.toString())
            .put("version", workflow.getVersion())
            .put("boundTaskId", String.valueOf(workflow.getSourceTaskId())));
  }

  @Override
  @Transactional
  public void reject(UUID workflowId, String feedback) {
    WorkflowEntity workflow = require(workflowId);
    workflow.setStatus(WorkflowStatus.REJECTED);
    workflow.setReviewFeedback(feedback);
    workflows.save(workflow);
    registry.snapshot(workflow);
    skills.refreshInvocability(workflowId);

    // A rejected procedure must not stay bound to a job, or the job would keep running it.
    if (workflow.getSourceTaskId() != null) {
      tasks.findById(workflow.getSourceTaskId())
          .filter(task -> workflowId.equals(task.getBoundWorkflowId()))
          .ifPresent(task -> scheduler.unbindProcedure(task.getId()));
    }
    audit.record(workflow.getUserId(), ActorType.USER, null, "procedure.rejected",
        workflow.getName(), Json.obj().put("workflowId", workflowId.toString())
            .put("feedback", String.valueOf(feedback)));
  }

  @Override
  @Transactional
  public WorkflowDto revise(UUID workflowId, JsonNode graphJson) {
    WorkflowEntity workflow = require(workflowId);
    WorkflowGraph graph = WorkflowGraph.from(graphJson);
    validator.validate(graph);

    if (graph.fingerprint().equals(workflow.getGraphFingerprint())) {
      throw new ValidationException("That is the same graph the procedure already has");
    }
    AgentEntity agent = workflow.getOwningAgentId() == null ? null
        : agents.findById(workflow.getOwningAgentId()).orElse(null);
    return submitForReview(workflow, graph, agent,
        "The procedure '%s' has changed and needs reviewing again.".formatted(workflow.getName()));
  }

  /** Bumps the version, drops back to review, unbinds, and asks the user to look again (R36). */
  private WorkflowDto submitForReview(WorkflowEntity workflow, WorkflowGraph graph,
                                      AgentEntity agent, String message) {
    workflow.setVersion(workflow.getVersion() + 1);
    workflow.setGraph(graph.toJson());
    workflow.setGraphFingerprint(graph.fingerprint());
    workflow.setStatus(WorkflowStatus.PENDING_REVIEW);
    workflow.setReviewFeedback(null);
    workflows.save(workflow);
    registry.snapshot(workflow);
    skills.refreshInvocability(workflow.getId());

    if (workflow.getSourceTaskId() != null) {
      tasks.findById(workflow.getSourceTaskId())
          .filter(task -> workflow.getId().equals(task.getBoundWorkflowId()))
          .ifPresent(task -> scheduler.unbindProcedure(task.getId()));
    }
    audit.record(workflow.getUserId(), ActorType.AGENT, workflow.getOwningAgentId(),
        "procedure.revised", workflow.getName(),
        Json.obj().put("workflowId", workflow.getId().toString())
            .put("version", workflow.getVersion()));
    raiseReview(workflow, agent, message);
    return DefaultWorkflowRegistry.toDto(workflow);
  }

  @Override
  @Transactional
  public void rollback(UUID workflowId, int toVersion) {
    WorkflowEntity workflow = require(workflowId);
    WorkflowVersionEntity snapshot = versions.findByWorkflowIdAndVersion(workflowId, toVersion)
        .orElseThrow(() -> new NotFoundException("Version " + toVersion + " of procedure", workflowId));

    if (snapshot.getStatus() != WorkflowStatus.APPROVED) {
      throw new ValidationException("Version " + toVersion + " was never approved, so rolling back "
          + "to it would put an unreviewed procedure into use");
    }

    // Rolling back to a version a human already approved is not a new change, so it does not
    // re-enter review; it becomes the current version and rebinds.
    workflow.setVersion(workflow.getVersion() + 1);
    workflow.setGraph(snapshot.getGraph());
    workflow.setGraphFingerprint(snapshot.getGraphFingerprint());
    workflow.setStatus(WorkflowStatus.APPROVED);
    workflow.setDisabled(false);
    workflows.save(workflow);
    registry.snapshot(workflow);
    skills.refreshInvocability(workflowId);

    if (workflow.getSourceTaskId() != null) {
      tasks.findById(workflow.getSourceTaskId()).ifPresent(task ->
          scheduler.bindProcedure(task.getId(), workflowId, workflow.getVersion()));
    }
  }

  @Override
  @Transactional
  public void disable(UUID workflowId) {
    WorkflowEntity workflow = require(workflowId);
    workflow.setDisabled(true);
    workflows.save(workflow);
    skills.refreshInvocability(workflowId);

    if (workflow.getSourceTaskId() != null) {
      tasks.findById(workflow.getSourceTaskId())
          .filter(task -> workflowId.equals(task.getBoundWorkflowId()))
          .ifPresent(task -> scheduler.unbindProcedure(task.getId()));
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<WorkflowDto> history(UUID workflowId) {
    return registry.versions(workflowId);
  }

  @Override
  @Transactional(readOnly = true)
  public ProcedureDiff diff(UUID workflowId, int fromVersion, int toVersion) {
    Map<String, WorkflowNode> from = nodesOf(workflowId, fromVersion);
    Map<String, WorkflowNode> to = nodesOf(workflowId, toVersion);

    List<String> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    List<String> changed = new ArrayList<>();

    to.forEach((id, node) -> {
      WorkflowNode before = from.get(id);
      if (before == null) {
        added.add(describe(node));
      } else if (!sameConfig(before, node)) {
        changed.add(describe(node));
      }
    });
    from.forEach((id, node) -> {
      if (!to.containsKey(id)) removed.add(describe(node));
    });
    return new ProcedureDiff(fromVersion, toVersion, added, removed, changed);
  }

  private Map<String, WorkflowNode> nodesOf(UUID workflowId, int version) {
    return registry.findVersion(workflowId, version)
        .map(dto -> WorkflowGraph.from(dto.graph()).nodes().stream()
            .collect(Collectors.toMap(WorkflowNode::id, Function.identity())))
        .orElseThrow(() -> new NotFoundException("Version " + version + " of procedure", workflowId));
  }

  private boolean sameConfig(WorkflowNode a, WorkflowNode b) {
    if (a.type() != b.type()) return false;
    String left = a.config() == null || a.config().isNull() ? "" : Json.write(a.config());
    String right = b.config() == null || b.config().isNull() ? "" : Json.write(b.config());
    return left.equals(right);
  }

  private String describe(WorkflowNode node) {
    return node.id() + " (" + node.type() + (node.label() == null ? "" : ": " + node.label()) + ")";
  }

  private void raiseReview(WorkflowEntity workflow, AgentEntity agent, String message) {
    NotificationBus bus = notifications.getIfAvailable();
    if (bus == null) {
      log.warn("Procedure {} needs review but the notification bus is unavailable",
          workflow.getName());
      return;
    }
    bus.raise(NotificationKind.WORKFLOW_REVIEW, agent == null ? null : agent.getId(),
        NotificationContext.forWorkflow(workflow.getUserId(), message,
            Json.obj().put("workflowId", workflow.getId().toString())
                .put("version", workflow.getVersion())
                .set("graph", workflow.getGraph()),
            workflow.getId(), workflow.getSourceTaskId()));
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasProposalFor(UUID sourceTaskId) {
    return !workflows.findBySourceTaskId(sourceTaskId).isEmpty();
  }

  private WorkflowEntity require(UUID workflowId) {
    return workflows.findById(workflowId)
        .orElseThrow(() -> new NotFoundException("Procedure", workflowId));
  }
}
