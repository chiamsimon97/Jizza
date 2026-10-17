package com.jizza.engine.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.RunStepStatus;
import com.jizza.engine.api.error.AutonomyDeniedException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.persistence.entity.WorkflowRunStepEntity;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import com.jizza.engine.persistence.repository.WorkflowRunStepRepository;
import com.jizza.engine.persistence.repository.WorkflowVersionRepository;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Postgres-backed saga engine (R33).
 *
 * <p>The whole design turns on one rule: <b>a node's result is committed before the next node
 * starts</b>. Every advance re-reads the run row and its checkpoints, so a process that dies
 * mid-flight loses at most the node that was in progress, and any process can pick the run back up
 * from its last completed node with no in-memory state to reconstruct.
 *
 * <p>On top of that: a stable per-node idempotency key makes side effects exactly-once, the kill
 * switch is checked at every checkpoint so pausing autonomy stops runs at a safe boundary, retries
 * back off and then either raise an intervention or dead-letter, and an intervention node stops the
 * run in a state that survives a restart untouched.
 */
@Service
public class SagaWorkflowEngine implements WorkflowEngine {

  private static final Logger log = LoggerFactory.getLogger(SagaWorkflowEngine.class);

  /** Ceiling on nodes executed in one advance, so a pathological graph cannot spin a worker. */
  private static final int MAX_NODES_PER_ADVANCE = 200;

  static final String PAUSED_FOR_INSTRUCTION = "instruction";
  static final String PAUSED_FOR_PERMISSION = "permission";

  private final WorkflowRepository workflows;
  private final WorkflowVersionRepository versions;
  private final WorkflowRunRepository runs;
  private final WorkflowRunStepRepository steps;
  private final WorkflowValidator validator;
  private final KillSwitch killSwitch;
  private final AuditLog audit;
  private final ObjectProvider<NotificationBus> notifications;
  private final Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);

  public SagaWorkflowEngine(WorkflowRepository workflows, WorkflowVersionRepository versions,
                            WorkflowRunRepository runs, WorkflowRunStepRepository steps,
                            WorkflowValidator validator, KillSwitch killSwitch, AuditLog audit,
                            ObjectProvider<NotificationBus> notifications,
                            List<NodeExecutor> nodeExecutors) {
    this.workflows = workflows;
    this.versions = versions;
    this.runs = runs;
    this.steps = steps;
    this.validator = validator;
    this.killSwitch = killSwitch;
    this.audit = audit;
    this.notifications = notifications;
    nodeExecutors.forEach(executor -> executors.put(executor.type(), executor));
  }

  @Override
  @Transactional
  public RunDto start(UUID workflowId, int version, JsonNode input, String triggerSource) {
    WorkflowEntity workflow = workflows.findById(workflowId)
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
    WorkflowGraph graph = graphFor(workflow, version);
    validator.validate(graph);

    // A run started by anything other than a person must use an approved version. Manual runs of a
    // draft are how a user tests what they are building.
    boolean manual = triggerSource != null && triggerSource.startsWith("manual");
    if (!manual && workflow.getStatus() != com.jizza.engine.api.enums.WorkflowStatus.APPROVED) {
      throw new ValidationException("Workflow '" + workflow.getName()
          + "' is not approved, so it cannot run unattended");
    }
    if (workflow.isDisabled()) {
      throw new ValidationException("Workflow '" + workflow.getName() + "' is disabled");
    }

    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setUserId(workflow.getUserId());
    run.setWorkflowId(workflowId);
    run.setVersion(version);
    run.setTriggerSource(triggerSource);
    run.setStatus(RunStatus.RUNNING);
    run.setCurrentNode(graph.trigger().id());
    run.setContext(RunContext.start(input).toJson());
    runs.save(run);

    audit.record(run.getUserId(), ActorType.SYSTEM, workflow.getOwningAgentId(), "run.started",
        workflow.getName(), Json.obj().put("runId", run.getId().toString())
            .put("version", version).put("trigger", String.valueOf(triggerSource)));

    return advance(run.getId());
  }

  @Override
  @Transactional
  public RunDto resume(UUID runId, JsonNode instruction) {
    WorkflowRunEntity run = requireRun(runId);
    if (run.getStatus() != RunStatus.PAUSED_WAITING_INSTRUCTION) {
      throw new ValidationException("Run " + runId + " is " + run.getStatus()
          + " and is not waiting for anything");
    }
    RunContext context = RunContext.of(run.getContext());
    String node = context.pausedNode() == null ? run.getCurrentNode() : context.pausedNode();
    String reason = context.pausedFor();

    if (PAUSED_FOR_PERMISSION.equals(reason)) {
      boolean approved = instruction != null && instruction.path("approved").asBoolean(false);
      if (!approved) {
        context.clearPause();
        run.setContext(context.toJson());
        failRun(run, node, "The user declined to approve this step");
        return toDto(run);
      }
      // Approving does not skip the node: it clears the pause so the node runs for real, and
      // records the approval so the node does not stop to ask the same question again.
      context.clearPause();
      context.recordApproval(node);
      run.setContext(context.toJson());
      run.setStatus(RunStatus.RUNNING);
      run.setCurrentNode(node);
      runs.save(run);
      audit.record(run.getUserId(), ActorType.USER, null, "run.permission_granted",
          run.getId().toString(), Json.obj().put("node", node));
      return advance(runId);
    }

    String text = instruction == null ? null
        : instruction.isTextual() ? instruction.asText()
        : instruction.path("instruction").asText(null);
    if (text == null || text.isBlank()) {
      throw new ValidationException("This run is waiting for an instruction, so one is required");
    }

    context.recordInstruction(node, text);
    context.clearPause();
    run.setContext(context.toJson());
    run.setStatus(RunStatus.RUNNING);
    run.setCurrentNode(node);
    runs.save(run);

    audit.record(run.getUserId(), ActorType.USER, null, "run.instruction_submitted",
        run.getId().toString(), Json.obj().put("node", node));
    return advance(runId);
  }

  @Override
  @Transactional
  public RunDto resumeAfterRestart(UUID runId) {
    WorkflowRunEntity run = requireRun(runId);
    if (run.getStatus() != RunStatus.RUNNING) return toDto(run);
    log.info("Resuming run {} from node {}", runId, run.getCurrentNode());
    audit.record(run.getUserId(), ActorType.SYSTEM, null, "run.resumed_after_restart",
        runId.toString(), Json.obj().put("fromNode", String.valueOf(run.getCurrentNode())));
    return advance(runId);
  }

  @Override
  @Transactional
  public void cancel(UUID runId) {
    WorkflowRunEntity run = requireRun(runId);
    run.setStatus(RunStatus.CANCELLED);
    runs.save(run);
    audit.record(run.getUserId(), ActorType.USER, null, "run.cancelled", runId.toString(), null);
  }

  @Override
  @Transactional(readOnly = true)
  public RunDto get(UUID runId) {
    return toDto(requireRun(runId));
  }

  /**
   * Drives the run forward from whatever node it is on, checkpointing as it goes.
   *
   * <p>Re-entrant by construction: calling it on a run that has already completed some nodes skips
   * straight past their checkpoints, which is what makes "resume from the last completed node" the
   * same code path as "keep going".
   */
  private RunDto advance(UUID runId) {
    for (int executed = 0; executed < MAX_NODES_PER_ADVANCE; executed++) {
      WorkflowRunEntity run = requireRun(runId);
      if (run.getStatus() != RunStatus.RUNNING) return toDto(run);

      // Checked here, between nodes: the kill switch halts autonomy at the next safe checkpoint
      // rather than tearing down whatever is mid-flight (R40).
      try {
        killSwitch.requireActive(run.getUserId());
      } catch (AutonomyDeniedException e) {
        log.info("Run {} halted at node {} because autonomy is paused", runId, run.getCurrentNode());
        audit.record(run.getUserId(), ActorType.SYSTEM, null, "run.halted_by_kill_switch",
            runId.toString(), Json.obj().put("node", String.valueOf(run.getCurrentNode())));
        return toDto(run);
      }

      WorkflowGraph graph = graphFor(workflows.findById(run.getWorkflowId())
          .orElseThrow(() -> new NotFoundException("Workflow", run.getWorkflowId())),
          run.getVersion());
      String nodeId = run.getCurrentNode();
      if (nodeId == null) return completeRun(run);

      WorkflowNode node = graph.node(nodeId).orElse(null);
      if (node == null) {
        failRun(run, nodeId, "The run points at node '" + nodeId + "', which is not in this "
            + "version of the workflow");
        return toDto(run);
      }

      RunContext context = RunContext.of(run.getContext());
      Optional<WorkflowRunStepEntity> checkpoint = steps.findByRunIdAndNodeId(runId, nodeId);

      if (checkpoint.isPresent() && checkpoint.get().getStatus() == RunStepStatus.DONE) {
        // Already done in an earlier life of this run: move on without re-executing. This is the
        // line that makes a side-effecting node exactly-once across a crash.
        if (!moveToNext(run, graph, node, context)) return completeRun(run);
        continue;
      }

      NodeExecutor executor = executors.get(node.type());
      if (executor == null) {
        failRun(run, nodeId, "No executor for node type " + node.type());
        return toDto(run);
      }

      int attempt = checkpoint.map(WorkflowRunStepEntity::getAttempts).orElse(0) + 1;
      NodeExecutor.Outcome outcome;
      try {
        outcome = executor.execute(new NodeExecutor.Execution(runId, run.getUserId(),
            owningAgentOf(run), node, context, attempt, run.getWorkflowId(), run.getVersion()));
      } catch (RuntimeException e) {
        log.warn("Node {} of run {} threw", nodeId, runId, e);
        outcome = NodeExecutor.Outcome.retryable(String.valueOf(e.getMessage()));
      }

      switch (outcome.status()) {
        case COMPLETED -> {
          context.recordOutput(nodeId, outcome.output());
          if (outcome.branch() != null) context.recordBranch(nodeId, outcome.branch());
          checkpoint(runId, nodeId, RunStepStatus.DONE, outcome.output(), attempt, null);
          run.setContext(context.toJson());
          if (!moveToNext(run, graph, node, context)) return completeRun(run);
        }
        case AWAIT_INSTRUCTION -> {
          return toDto(pause(run, context, nodeId, PAUSED_FOR_INSTRUCTION, outcome.message(),
              NotificationKind.INSTRUCTION_REQUIRED));
        }
        case AWAIT_PERMISSION -> {
          return toDto(pause(run, context, nodeId, PAUSED_FOR_PERMISSION, outcome.message(),
              NotificationKind.PERMISSION_REQUEST));
        }
        case PERMANENT_FAILURE -> {
          checkpoint(runId, nodeId, RunStepStatus.FAILED, null, attempt, outcome.message());
          return toDto(handleExhausted(run, node, context, outcome.message()));
        }
        case RETRYABLE -> {
          int maxAttempts = node.retryOrDefault().maxAttempts();
          checkpoint(runId, nodeId, RunStepStatus.FAILED, null, attempt, outcome.message());
          if (attempt >= maxAttempts) {
            return toDto(handleExhausted(run, node, context,
                outcome.message() + " (after " + attempt + " attempts)"));
          }
          backoff(node, attempt);
          // Loop round and retry the same node; the attempt count lives in the checkpoint, so a
          // restart mid-retry does not reset it.
        }
      }
    }
    log.error("Run {} exceeded {} nodes in one advance", runId, MAX_NODES_PER_ADVANCE);
    WorkflowRunEntity run = requireRun(runId);
    failRun(run, run.getCurrentNode(), "The run executed too many nodes and was stopped");
    return toDto(run);
  }

  /** @return false when there is nowhere further to go, meaning the run is finished. */
  private boolean moveToNext(WorkflowRunEntity run, WorkflowGraph graph, WorkflowNode node,
                             RunContext context) {
    Optional<String> next = node.type() == NodeType.BRANCH
        ? graph.next(node.id(), context.branchOf(node.id()))
        : graph.next(node.id());
    if (next.isEmpty()) return false;
    run.setCurrentNode(next.get());
    run.setUpdatedAt(Instant.now());
    runs.save(run);
    return true;
  }

  private WorkflowRunEntity pause(WorkflowRunEntity run, RunContext context, String nodeId,
                                  String reason, String prompt, NotificationKind kind) {
    context.pause(reason, nodeId);
    run.setContext(context.toJson());
    run.setCurrentNode(nodeId);
    run.setStatus(RunStatus.PAUSED_WAITING_INSTRUCTION);
    run.setUpdatedAt(Instant.now());
    runs.save(run);

    NotificationBus bus = notifications.getIfAvailable();
    if (bus != null) {
      bus.raise(kind, owningAgentOf(run), NotificationContext.forRun(run.getUserId(),
          prompt == null ? "This run needs your input to continue." : prompt,
          Json.obj().put("node", nodeId).put("workflowId", run.getWorkflowId().toString()),
          run.getId()));
    } else {
      log.warn("Run {} paused at {} but the notification bus is unavailable, so nobody was told",
          run.getId(), nodeId);
    }
    audit.record(run.getUserId(), ActorType.SYSTEM, owningAgentOf(run), "run.paused",
        run.getId().toString(), Json.obj().put("node", nodeId).put("reason", reason));
    return run;
  }

  /**
   * A node that has run out of attempts either raises an intervention -- the default, because a
   * permanent failure should reach a human rather than vanish -- or dead-letters when the node
   * explicitly says so.
   */
  private WorkflowRunEntity handleExhausted(WorkflowRunEntity run, WorkflowNode node,
                                            RunContext context, String message) {
    String onFailure = node.config() == null
        ? "intervene" : node.config().path("onFailure").asText("intervene");
    if ("dead_letter".equals(onFailure)) {
      run.setDeadLettered(true);
      failRun(run, node.id(), message);
      audit.record(run.getUserId(), ActorType.SYSTEM, owningAgentOf(run), "run.dead_lettered",
          run.getId().toString(), Json.obj().put("node", node.id())
              .put("error", String.valueOf(message)));
      return run;
    }
    return pause(run, context, node.id(), PAUSED_FOR_INSTRUCTION,
        "Step '" + (node.label() == null ? node.id() : node.label()) + "' could not complete: "
            + message + " How would you like to proceed?",
        NotificationKind.INSTRUCTION_REQUIRED);
  }

  private RunDto completeRun(WorkflowRunEntity run) {
    run.setStatus(RunStatus.DONE);
    run.setCurrentNode(null);
    run.setUpdatedAt(Instant.now());
    runs.save(run);
    audit.record(run.getUserId(), ActorType.SYSTEM, owningAgentOf(run), "run.completed",
        run.getId().toString(), null);
    return toDto(run);
  }

  private void failRun(WorkflowRunEntity run, String nodeId, String message) {
    run.setStatus(RunStatus.FAILED);
    run.setCurrentNode(nodeId);
    run.setError(message);
    run.setUpdatedAt(Instant.now());
    runs.save(run);
    audit.record(run.getUserId(), ActorType.SYSTEM, owningAgentOf(run), "run.failed",
        run.getId().toString(), Json.obj().put("node", String.valueOf(nodeId))
            .put("error", String.valueOf(message)));
  }

  /** Upserts the per-node checkpoint. The unique (run_id, node_id) index keeps it to one row. */
  private void checkpoint(UUID runId, String nodeId, RunStepStatus status, JsonNode output,
                          int attempts, String error) {
    WorkflowRunStepEntity step = steps.findByRunIdAndNodeId(runId, nodeId)
        .orElseGet(() -> {
          WorkflowRunStepEntity fresh = new WorkflowRunStepEntity();
          fresh.setRunId(runId);
          fresh.setNodeId(nodeId);
          fresh.setStartedAt(Instant.now());
          return fresh;
        });
    step.setStatus(status);
    step.setOutput(output);
    step.setAttempts(attempts);
    step.setError(error);
    if (status == RunStepStatus.DONE) step.setFinishedAt(Instant.now());
    steps.save(step);
  }

  private void backoff(WorkflowNode node, int attempt) {
    var policy = node.retryOrDefault();
    long delay = (long) (policy.backoffMillis() * Math.pow(policy.multiplier(), attempt - 1.0));
    long capped = Math.min(delay, policy.timeoutMillis());
    try {
      Thread.sleep(capped);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private UUID owningAgentOf(WorkflowRunEntity run) {
    return workflows.findById(run.getWorkflowId())
        .map(WorkflowEntity::getOwningAgentId).orElse(null);
  }

  private WorkflowGraph graphFor(WorkflowEntity workflow, int version) {
    if (workflow.getVersion() == version) return WorkflowGraph.from(workflow.getGraph());
    return versions.findByWorkflowIdAndVersion(workflow.getId(), version)
        .map(v -> WorkflowGraph.from(v.getGraph()))
        .orElseThrow(() -> new NotFoundException(
            "Version " + version + " of workflow", workflow.getId()));
  }

  private WorkflowRunEntity requireRun(UUID runId) {
    return runs.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run", runId));
  }

  RunDto toDto(WorkflowRunEntity run) {
    List<RunDto.StepDto> stepDtos = steps.findByRunIdOrderByStartedAtAsc(run.getId()).stream()
        .map(s -> new RunDto.StepDto(s.getId(), s.getNodeId(), s.getStatus(), s.getOutput(),
            s.getAttempts(), s.getFinishedAt()))
        .toList();
    return new RunDto(run.getId(), run.getUserId(), run.getWorkflowId(), run.getVersion(),
        run.getTriggerSource(), run.getStatus(), run.getCurrentNode(), run.getContext(),
        run.getCreatedAt(), run.getUpdatedAt(), stepDtos);
  }
}
