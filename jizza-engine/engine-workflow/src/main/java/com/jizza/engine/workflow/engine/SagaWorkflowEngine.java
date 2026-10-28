package com.jizza.engine.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.persistence.entity.WorkflowRunStepEntity;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import com.jizza.engine.persistence.repository.WorkflowRunStepRepository;
import com.jizza.engine.persistence.repository.WorkflowVersionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p><b>Several paths at once.</b> The run keeps a frontier in its context: the steps that can run
 * now and the steps parked on a time, on workflows they started, or on a person. A step with more
 * than one incoming path runs once every path has resolved, either by reaching it or by being cut
 * off by a branch that went the other way (dead-path elimination), so a parallel split joins
 * exactly once and a branch that merges back never waits for the side it did not take. Paths are
 * interleaved on the run's own worker rather than on separate threads, which keeps one transaction
 * and one activity context per run; what parallel buys is that a path waiting on a person or a
 * timer no longer holds up the others.
 */
@Service
public class SagaWorkflowEngine implements WorkflowEngine {

  private static final Logger log = LoggerFactory.getLogger(SagaWorkflowEngine.class);

  /** Ceiling on nodes executed in one advance, so a pathological graph cannot spin a worker. */
  private static final int MAX_NODES_PER_ADVANCE = 200;

  /** How deep sub-workflows may nest: a workflow running one that runs one, and so on. */
  static final int MAX_DEPTH = 5;

  static final String PAUSED_FOR_INSTRUCTION = "instruction";
  static final String PAUSED_FOR_PERMISSION = "permission";
  static final String PARKED_ON_TIMER = "timer";
  static final String PARKED_ON_CHILDREN = "children";
  static final String PARKED_ON_DEVICE = "device";
  /** Nodes whose result picks which of their outgoing paths are taken. */
  private static final Set<NodeType> ROUTES_BY_OUTLET =
      Set.of(NodeType.BRANCH, NodeType.SWITCH, NodeType.APPROVAL, NodeType.FILTER);
  /** The outlet a filter records when its condition held. */
  public static final String FILTER_PASSED = "pass";

  private final WorkflowRepository workflows;
  private final WorkflowVersionRepository versions;
  private final WorkflowRunRepository runs;
  private final WorkflowRunStepRepository steps;
  private final WorkflowValidator validator;
  private final KillSwitch killSwitch;
  private final AuditLog audit;
  private final ObjectProvider<NotificationBus> notifications;
  private final Clock clock;
  private final StepInputs stepInputs;
  private final Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);
  private ObjectProvider<com.jizza.engine.api.device.DeviceCommands> deviceCommands;

  @Autowired
  public SagaWorkflowEngine(WorkflowRepository workflows, WorkflowVersionRepository versions,
                            WorkflowRunRepository runs, WorkflowRunStepRepository steps,
                            WorkflowValidator validator, KillSwitch killSwitch, AuditLog audit,
                            ObjectProvider<NotificationBus> notifications,
                            List<NodeExecutor> nodeExecutors, JexlSandbox sandbox) {
    this(workflows, versions, runs, steps, validator, killSwitch, audit, notifications,
        nodeExecutors, Clock.systemUTC(), sandbox);
  }

  public SagaWorkflowEngine(WorkflowRepository workflows, WorkflowVersionRepository versions,
                            WorkflowRunRepository runs, WorkflowRunStepRepository steps,
                            WorkflowValidator validator, KillSwitch killSwitch, AuditLog audit,
                            ObjectProvider<NotificationBus> notifications,
                            List<NodeExecutor> nodeExecutors, Clock clock, JexlSandbox sandbox) {
    this.workflows = workflows;
    this.versions = versions;
    this.runs = runs;
    this.steps = steps;
    this.validator = validator;
    this.killSwitch = killSwitch;
    this.audit = audit;
    this.notifications = notifications;
    this.clock = clock;
    this.stepInputs = new StepInputs(sandbox);
    nodeExecutors.forEach(executor -> executors.put(executor.type(), executor));
  }

  /** Paired devices, when the app has them, so a stopped run stops its device commands too. */
  @Autowired(required = false)
  public void setDeviceCommands(
      ObjectProvider<com.jizza.engine.api.device.DeviceCommands> deviceCommands) {
    this.deviceCommands = deviceCommands;
  }

  @Override
  @Transactional
  public RunDto start(UUID workflowId, int version, JsonNode input, String triggerSource) {
    WorkflowEntity workflow = workflows.findById(workflowId)
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
    return launch(workflow, version, input, triggerSource, context -> { });
  }

  /**
   * Starts {@code workflowId} as a step of another run. The child belongs to the same person,
   * must not already be running further up the chain, and follows the same approval rule as any
   * run: only a person testing by hand may run a draft. When the child finishes, the parent is
   * woken to pick up its result.
   *
   * @throws ValidationException when nesting is too deep, the workflow would run itself, or it
   *                             is not allowed to run unattended
   * @throws NotFoundException   when there is no such workflow for this person
   */
  @Transactional
  public RunDto startChild(UUID parentRunId, String parentNodeId, UUID workflowId,
                           JsonNode input, boolean inheritBrowser) {
    WorkflowRunEntity parent = requireRun(parentRunId);
    RunContext parentContext = RunContext.of(parent.getContext());
    int depth = parentContext.depth() + 1;
    if (depth > MAX_DEPTH) {
      throw new ValidationException("Workflows can call other workflows at most " + MAX_DEPTH
          + " levels deep");
    }

    WorkflowEntity workflow = workflows.findById(workflowId)
        .filter(w -> Objects.equals(w.getUserId(), parent.getUserId()))
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));

    Set<UUID> seen = new HashSet<>();
    for (WorkflowRunEntity up = parent; up != null && seen.add(up.getId()); ) {
      if (up.getWorkflowId().equals(workflowId)) {
        throw new ValidationException("Workflow '" + workflow.getName()
            + "' is already running further up this chain, so it cannot run itself as a step");
      }
      UUID next = RunContext.of(up.getContext()).parentRunId();
      up = next == null ? null : runs.findById(next).orElse(null);
    }

    boolean manual = parent.getTriggerSource() != null
        && parent.getTriggerSource().startsWith("manual");
    String trigger = (manual ? "manual:" : "") + "subworkflow:" + parentRunId;
    return launch(workflow, workflow.getVersion(), input, trigger, context -> {
      context.recordParent(parentRunId, parentNodeId, depth);
      RunContext.Browser browser = parentContext.browser();
      if (inheritBrowser && browser != null) {
        context.setBrowser(new RunContext.Browser(
            browser.deviceId(), browser.workspaceId(), browser.sessionId(), false));
      } else if (!inheritBrowser) {
        context.requireNewBrowserSession();
      }
    });
  }

  public RunDto startChild(UUID parentRunId, String parentNodeId, UUID workflowId,
                           JsonNode input) {
    return startChild(parentRunId, parentNodeId, workflowId, input, true);
  }

  /** A library run of the current version, whose steps are the ones the user allowed unattended. */
  private static boolean allowedUnattended(WorkflowEntity workflow, int version, String triggerSource) {
    if (triggerSource == null || !triggerSource.startsWith("library:")) return false;
    if (version != workflow.getVersion()) return false;
    var profile = com.jizza.engine.api.workflow.library.ReuseProfile.from(workflow.getReuse());
    return profile != null && profile.guardrails().allowsUnattended(workflow.getGraphFingerprint());
  }

  private RunDto launch(WorkflowEntity workflow, int version, JsonNode input, String triggerSource,
                        Consumer<RunContext> setUp) {
    WorkflowGraph graph = graphFor(workflow, version);
    validator.validate(graph);

    // A run started by anything other than a person must use an approved version. Manual runs of a
    // draft are how a user tests what they are building. The one other way is the user allowing a
    // library workflow to run on a schedule, which holds only for the steps they allowed it with.
    boolean manual = triggerSource != null && triggerSource.startsWith("manual");
    if (!manual && workflow.getStatus() != com.jizza.engine.api.enums.WorkflowStatus.APPROVED
        && !allowedUnattended(workflow, version, triggerSource)) {
      throw new ValidationException("Workflow '" + workflow.getName()
          + "' is not approved, so it cannot run unattended");
    }
    if (workflow.isDisabled()) {
      throw new ValidationException("Workflow '" + workflow.getName() + "' is disabled");
    }

    // A module's declared inputs: defaults filled in, values typed, required ones present.
    input = com.jizza.engine.api.workflow.graph.ModuleContract.prepareInput(graph, input);

    RunContext context = RunContext.start(input);
    context.startFrontier(graph.trigger().id(), false);
    setUp.accept(context);

    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setUserId(workflow.getUserId());
    run.setWorkflowId(workflow.getId());
    run.setVersion(version);
    run.setTriggerSource(triggerSource);
    run.setStatus(RunStatus.RUNNING);
    run.setCurrentNode(graph.trigger().id());
    run.setContext(context.toJson());
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
    if (run.getStatus() == RunStatus.DONE || run.getStatus() == RunStatus.FAILED
        || run.getStatus() == RunStatus.CANCELLED) {
      throw new ValidationException("Run " + runId + " is " + run.getStatus()
          + " and is not waiting for anything");
    }
    RunContext context = RunContext.of(run.getContext());
    String node = waitingStep(run, context, instruction);
    String reason = context.parked().getOrDefault(node, context.pausedFor());

    if (PAUSED_FOR_PERMISSION.equals(reason)) {
      boolean approved = instruction != null && instruction.path("approved").asBoolean(false);
      if (isApprovalStep(run, node)) {
        // An approval step's answer is its result, either way: it picks the path, never fails.
        clearPause(context, node);
        ApprovalDecisions.record(context, node, approved ? "approved" : "rejected",
            instruction == null ? null : instruction.path("feedback").asText(null));
        run.setContext(context.toJson());
        run.setStatus(RunStatus.RUNNING);
        run.setCurrentNode(node);
        runs.save(run);
        audit.record(run.getUserId(), ActorType.USER, null,
            approved ? "run.approval_approved" : "run.approval_rejected",
            run.getId().toString(), Json.obj().put("node", node));
        return advance(runId);
      }
      if (!approved) {
        clearPause(context, node);
        context.dequeue(node);
        run.setContext(context.toJson());
        failRun(run, node, "The user declined to approve this step");
        return toDto(run);
      }
      // Approving does not skip the node: it clears the pause so the node runs for real, and
      // records the approval so the node does not stop to ask the same question again.
      clearPause(context, node);
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
    clearPause(context, node);
    forgetFailedChildren(context, node);
    run.setContext(context.toJson());
    run.setStatus(RunStatus.RUNNING);
    run.setCurrentNode(node);
    runs.save(run);

    audit.record(run.getUserId(), ActorType.USER, null, "run.instruction_submitted",
        run.getId().toString(), Json.obj().put("node", node));
    return advance(runId);
  }

  /**
   * Which step an answer is for. With one step waiting on a person that is the one; with several
   * (two parallel paths both asking) the answer must name it as {@code "node"}, which the
   * notification it answers carries.
   */
  private String waitingStep(WorkflowRunEntity run, RunContext context, JsonNode instruction) {
    List<String> waiting = context.parked().entrySet().stream()
        .filter(e -> PAUSED_FOR_INSTRUCTION.equals(e.getValue())
            || PAUSED_FOR_PERMISSION.equals(e.getValue()))
        .map(Map.Entry::getKey).toList();
    String named = instruction != null && instruction.path("node").isTextual()
        ? instruction.get("node").asText() : null;
    if (named != null && waiting.contains(named)) return named;
    if (waiting.size() == 1) return waiting.get(0);
    if (waiting.isEmpty() && run.getStatus() == RunStatus.PAUSED_WAITING_INSTRUCTION) {
      // A run paused before steps were parked individually.
      return context.pausedNode() == null ? run.getCurrentNode() : context.pausedNode();
    }
    if (waiting.isEmpty()) {
      throw new ValidationException("Run " + run.getId() + " is " + run.getStatus()
          + " and is not waiting for anything");
    }
    throw new ValidationException("Several steps of this run are waiting for you; say which one "
        + "this answer is for");
  }

  @Override
  @Transactional
  public RunDto resumeAfterRestart(UUID runId) {
    WorkflowRunEntity run = requireRun(runId);
    if (run.getStatus() != RunStatus.RUNNING) return toDto(run);
    log.info("Resuming run {} from node {}", runId, run.getCurrentNode());
    audit.record(run.getUserId(), ActorType.SYSTEM, null, "run.resumed_after_restart",
        runId.toString(), Json.obj().put("fromNode", String.valueOf(run.getCurrentNode())));
    // A run parked for a person is never saved as running, so one that is was set running on
    // purpose (by hand, or a step died between the two): its paused steps run again.
    RunContext context = RunContext.of(run.getContext());
    List<String> pausedSteps = context.parked().entrySet().stream()
        .filter(e -> PAUSED_FOR_INSTRUCTION.equals(e.getValue())
            || PAUSED_FOR_PERMISSION.equals(e.getValue()))
        .map(Map.Entry::getKey).toList();
    if (!pausedSteps.isEmpty()) {
      pausedSteps.forEach(nodeId -> clearPause(context, nodeId));
      save(run, context);
    }
    return advance(runId);
  }

  /**
   * Re-checks a run whose steps are parked: a wait step whose time has come, or a step waiting
   * on workflows it started or on a device. Does nothing, and costs one read, when nothing is due.
   */
  @Transactional
  public RunDto wake(UUID runId) {
    WorkflowRunEntity run = requireRun(runId);
    if (run.getStatus() != RunStatus.WAITING
        && run.getStatus() != RunStatus.PAUSED_WAITING_INSTRUCTION) {
      return toDto(run);
    }
    RunContext context = RunContext.of(run.getContext());
    Instant now = clock.instant();
    boolean due = false;
    for (Map.Entry<String, String> parked : context.parked().entrySet()) {
      boolean timerDue = PARKED_ON_TIMER.equals(parked.getValue())
          && !now.isBefore(Objects.requireNonNullElse(context.wakeAt(parked.getKey()), now));
      // An approval with a deadline is asked again once it passes, and takes its timeout path.
      Instant deadline = context.wakeAt(parked.getKey());
      boolean approvalLapsed = PAUSED_FOR_PERMISSION.equals(parked.getValue())
          && deadline != null && !now.isBefore(deadline);
      if (approvalLapsed) {
        clearPause(context, parked.getKey());
        due = true;
      } else if (timerDue || PARKED_ON_CHILDREN.equals(parked.getValue())
          || PARKED_ON_DEVICE.equals(parked.getValue())) {
        context.unpark(parked.getKey());
        due = true;
      }
    }
    if (!due) return toDto(run);
    run.setContext(context.toJson());
    run.setStatus(RunStatus.RUNNING);
    runs.save(run);
    return advance(runId);
  }

  @Override
  @Transactional
  public void cancel(UUID runId) {
    WorkflowRunEntity run = requireRun(runId);
    run.setStatus(RunStatus.CANCELLED);
    runs.save(run);
    audit.record(run.getUserId(), ActorType.USER, null, "run.cancelled", runId.toString(), null);
    RunContext context = RunContext.of(run.getContext());
    cancelChildren(context);
    cancelDeviceCommands(runId, "The run was cancelled");
    WorkflowGraph graph = graphFor(workflows.findById(run.getWorkflowId())
        .orElseThrow(() -> new NotFoundException("Workflow", run.getWorkflowId())),
        run.getVersion());
    cleanupBrowser(run, graph, context);
    save(run, context);
    wakeParent(context);
  }

  @Override
  @Transactional(readOnly = true)
  public RunDto get(UUID runId) {
    return toDto(requireRun(runId));
  }

  /**
   * Drives the run forward from its frontier, checkpointing as it goes.
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
      RunContext context = RunContext.of(run.getContext());
      if (!context.hasFrontier()) {
        // Started before runs tracked a frontier: carry on from the one node it was on.
        context.startFrontier(run.getCurrentNode(), true);
      }

      List<String> ready = context.ready();
      if (ready.isEmpty()) return settle(run, graph, context);
      String nodeId = ready.get(0);
      run.setCurrentNode(nodeId);

      WorkflowNode node = graph.node(nodeId).orElse(null);
      if (node == null) {
        failRun(run, nodeId, "The run points at node '" + nodeId + "', which is not in this "
            + "version of the workflow");
        return toDto(run);
      }

      Optional<WorkflowRunStepEntity> checkpoint = steps.findByRunIdAndNodeId(runId, nodeId);

      if (checkpoint.isPresent() && checkpoint.get().getStatus() == RunStepStatus.DONE) {
        // Already done in an earlier life of this run: move on without re-executing. This is the
        // line that makes a side-effecting node exactly-once across a crash.
        context.recordOutput(nodeId, checkpoint.get().getOutput());
        if (node.type() == NodeType.SET_VARIABLE) {
          SetVariables.apply(context, checkpoint.get().getOutput());
        }
        if (browserDeviceAction(node)) {
          BrowserAffinity.apply(context, checkpoint.get().getOutput());
        }
        finishNode(run, graph, node, context);
        continue;
      }

      NodeExecutor executor = executors.get(node.type());
      if (executor == null) {
        failRun(run, nodeId, "No executor for node type " + node.type());
        return toDto(run);
      }

      int attempt = checkpoint.map(WorkflowRunStepEntity::getAttempts).orElse(0) + 1;
      Instant stepStarted = Instant.now();
      JsonNode stepInput = stepInputs.snapshot(node, context);
      NodeExecutor.Outcome outcome;
      try {
        outcome = executor.execute(new NodeExecutor.Execution(runId, run.getUserId(),
            owningAgentOf(run), node, context, attempt, run.getWorkflowId(), run.getVersion(),
            graph));
      } catch (RuntimeException e) {
        log.warn("Node {} of run {} threw", nodeId, runId, e);
        outcome = NodeExecutor.Outcome.retryable(String.valueOf(e.getMessage()));
      }

      // A child run started by this node may have finished and touched this row; carry on from
      // the latest copy, with the context as this node left it.
      WorkflowRunEntity current = requireRun(runId);
      if (current.getStatus() != RunStatus.RUNNING) return toDto(current);

      switch (outcome.status()) {
        case COMPLETED -> {
          context.recordOutput(nodeId, outcome.output());
          if (browserDeviceAction(node)) BrowserAffinity.apply(context, outcome.output());
          if (outcome.branch() != null) context.recordBranch(nodeId, outcome.branch());
          checkpoint(runId, nodeId, RunStepStatus.DONE, stepInput, outcome.output(), attempt,
              null, stepStarted);
          finishNode(current, graph, node, context);
        }
        case AWAIT_INSTRUCTION -> parkForPerson(current, context, nodeId, PAUSED_FOR_INSTRUCTION,
            outcome.message(), NotificationKind.INSTRUCTION_REQUIRED);
        case AWAIT_PERMISSION -> parkForPerson(current, context, nodeId, PAUSED_FOR_PERMISSION,
            outcome.message(), NotificationKind.PERMISSION_REQUEST, outcome.wakeAt());
        case WAIT -> {
          context.park(nodeId, PARKED_ON_TIMER, outcome.wakeAt());
          save(current, context);
        }
        case AWAIT_CHILDREN -> {
          context.park(nodeId, PARKED_ON_CHILDREN, null);
          save(current, context);
        }
        case AWAIT_DEVICE -> {
          context.park(nodeId, PARKED_ON_DEVICE, null);
          save(current, context);
        }
        case PERMANENT_FAILURE -> {
          checkpoint(runId, nodeId, RunStepStatus.FAILED, stepInput, null, attempt,
              outcome.message(), stepStarted);
          if (handleExhausted(current, node, context, outcome.message())) return toDto(current);
        }
        case RETRYABLE -> {
          int maxAttempts = node.retryOrDefault().maxAttempts();
          checkpoint(runId, nodeId, RunStepStatus.FAILED, stepInput, null, attempt,
              outcome.message(), stepStarted);
          if (attempt >= maxAttempts) {
            if (handleExhausted(current, node, context,
                outcome.message() + " (after " + attempt + " attempts)")) {
              return toDto(current);
            }
          } else {
            save(current, context);
            backoff(node, attempt);
            // Loop round and retry the same node; the attempt count lives in the checkpoint, so a
            // restart mid-retry does not reset it.
          }
        }
      }
    }
    log.error("Run {} exceeded {} nodes in one advance", runId, MAX_NODES_PER_ADVANCE);
    WorkflowRunEntity run = requireRun(runId);
    failRun(run, run.getCurrentNode(), "The run executed too many nodes and was stopped");
    return toDto(run);
  }

  private boolean isApprovalStep(WorkflowRunEntity run, String nodeId) {
    return workflows.findById(run.getWorkflowId())
        .map(w -> graphFor(w, run.getVersion()))
        .flatMap(g -> g.node(nodeId))
        .map(n -> n.type() == NodeType.APPROVAL)
        .orElse(false);
  }

  /** A node is done: take it off the frontier and send its result down every path it opens. */
  private void finishNode(WorkflowRunEntity run, WorkflowGraph graph, WorkflowNode node,
                          RunContext context) {
    context.dequeue(node.id());
    context.recordLast(node.id());
    String outlet = ROUTES_BY_OUTLET.contains(node.type()) ? context.branchOf(node.id()) : null;
    for (WorkflowEdge edge : graph.outgoing(node.id())) {
      boolean live = switch (node.type()) {
        case BRANCH, SWITCH, APPROVAL -> Objects.equals(edge.branch(), outlet);
        // A filter has one way out, open only when its condition held.
        case FILTER -> FILTER_PASSED.equals(outlet);
        default -> true;
      };
      reach(run, graph, context, edge.to(), node.id(), live);
    }
    save(run, context);
  }

  /**
   * One incoming path of {@code to} has resolved. Once all of them have, the node runs if any
   * arrived and is skipped if none did, which resolves its own outgoing paths in turn.
   */
  private void reach(WorkflowRunEntity run, WorkflowGraph graph, RunContext context, String to,
                     String from, boolean live) {
    context.arrive(to, from, live);
    if (context.ready().contains(to) || context.isParked(to)
        || steps.findByRunIdAndNodeId(run.getId(), to).map(s -> s.getStatus() != RunStepStatus.FAILED)
            .orElse(false)) {
      return;
    }
    if (context.legacy()) {
      // Runs from before the frontier never recorded the paths their branches skipped, so they
      // keep the old rule: the first path to arrive runs the node.
      if (live) context.enqueue(to);
      return;
    }
    Map<String, Boolean> arrived = context.arrivals(to);
    boolean allResolved = graph.incoming(to).stream()
        .allMatch(edge -> arrived.containsKey(edge.from()));
    if (!allResolved) return;
    if (arrived.containsValue(true)) {
      context.enqueue(to);
      return;
    }
    checkpoint(run.getId(), to, RunStepStatus.SKIPPED, null, null, 0, null, Instant.now());
    for (WorkflowEdge edge : graph.outgoing(to)) reach(run, graph, context, edge.to(), to, false);
  }

  /**
   * Nothing can run right now. With nothing parked the run is finished; otherwise it waits for a
   * person if anyone owes it an answer, and for its timers and child runs if not.
   */
  private RunDto settle(WorkflowRunEntity run, WorkflowGraph graph, RunContext context) {
    Map<String, String> parked = context.parked();
    if (parked.isEmpty()) return completeRun(run, graph, context);

    Optional<Map.Entry<String, String>> person = parked.entrySet().stream()
        .filter(e -> PAUSED_FOR_INSTRUCTION.equals(e.getValue())
            || PAUSED_FOR_PERMISSION.equals(e.getValue()))
        .findFirst();
    boolean wasWaiting = run.getStatus() == RunStatus.WAITING;
    if (person.isPresent()) {
      run.setStatus(RunStatus.PAUSED_WAITING_INSTRUCTION);
      run.setCurrentNode(person.get().getKey());
      // Kept for anything that still reads the single paused step.
      context.pause(person.get().getValue(), person.get().getKey());
    } else {
      run.setStatus(RunStatus.WAITING);
      run.setCurrentNode(parked.keySet().iterator().next());
    }
    save(run, context);
    if (run.getStatus() == RunStatus.WAITING && !wasWaiting) {
      Instant soonest = parked.keySet().stream().map(context::wakeAt).filter(Objects::nonNull)
          .min(Instant::compareTo).orElse(null);
      audit.record(run.getUserId(), ActorType.SYSTEM, owningAgentOf(run), "run.waiting",
          run.getId().toString(), Json.obj().put("node", run.getCurrentNode())
              .put("until", String.valueOf(soonest)));
    }
    return toDto(run);
  }

  private void parkForPerson(WorkflowRunEntity run, RunContext context, String nodeId,
                             String reason, String prompt, NotificationKind kind) {
    parkForPerson(run, context, nodeId, reason, prompt, kind, null);
  }

  /** As above; with {@code until}, the recovery sweep runs the step again once that time passes. */
  private void parkForPerson(WorkflowRunEntity run, RunContext context, String nodeId,
                             String reason, String prompt, NotificationKind kind, Instant until) {
    context.park(nodeId, reason, until);
    save(run, context);

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
  }

  private void clearPause(RunContext context, String nodeId) {
    if (!context.hasFrontier()) context.startFrontier(null, true);
    context.unpark(nodeId);
    if (nodeId.equals(context.pausedNode()) || context.pausedNode() == null) context.clearPause();
  }

  /**
   * An answer to a failed step means "try it again". For a step that runs other workflows, that
   * means starting the ones that failed again, while keeping the ones that finished.
   */
  private void forgetFailedChildren(RunContext context, String nodeId) {
    if (!context.hasState(nodeId)) return;
    JsonNode children = context.state(nodeId).path("children");
    if (!children.isArray()) return;
    ArrayNode kept = Json.mapper().createArrayNode();
    for (JsonNode child : children) {
      RunStatus status = runs.findById(UUID.fromString(child.path("runId").asText()))
          .map(WorkflowRunEntity::getStatus).orElse(RunStatus.FAILED);
      if (status != RunStatus.FAILED && status != RunStatus.CANCELLED) kept.add(child);
    }
    context.state(nodeId).set("children", kept);
  }

  /**
   * A node that has run out of attempts either raises an intervention -- the default, because a
   * permanent failure should reach a human rather than vanish -- or dead-letters when the node
   * explicitly says so.
   *
   * @return true when the run has ended
   */
  private boolean handleExhausted(WorkflowRunEntity run, WorkflowNode node, RunContext context,
                                  String message) {
    String onFailure = node.config() == null
        ? "intervene" : node.config().path("onFailure").asText("intervene");
    if ("dead_letter".equals(onFailure)) {
      run.setDeadLettered(true);
      context.dequeue(node.id());
      run.setContext(context.toJson());
      failRun(run, node.id(), message);
      audit.record(run.getUserId(), ActorType.SYSTEM, owningAgentOf(run), "run.dead_lettered",
          run.getId().toString(), Json.obj().put("node", node.id())
              .put("error", String.valueOf(message)));
      return true;
    }
    parkForPerson(run, context, node.id(), PAUSED_FOR_INSTRUCTION,
        "Step '" + (node.label() == null ? node.id() : node.label()) + "' could not complete: "
            + message + " How would you like to proceed?",
        NotificationKind.INSTRUCTION_REQUIRED);
    return false;
  }

  private RunDto completeRun(WorkflowRunEntity run, WorkflowGraph graph, RunContext context) {
    context.recordResult(resultOf(graph, context));
    cleanupBrowser(run, graph, context);
    run.setContext(context.toJson());
    run.setStatus(RunStatus.DONE);
    run.setCurrentNode(null);
    run.setUpdatedAt(Instant.now());
    runs.save(run);
    audit.record(run.getUserId(), ActorType.SYSTEM, owningAgentOf(run), "run.completed",
        run.getId().toString(), null);
    wakeParent(context);
    return toDto(run);
  }

  private boolean browserDeviceAction(WorkflowNode node) {
    return node.type() == NodeType.DEVICE_ACTION && node.config() != null
        && node.config().path("action").asText().startsWith("browser.");
  }

  /**
   * What the run produced, handed to a parent run's step: the output of the end step it reached,
   * of each end step by id when parallel paths reached several, or of the last step to finish
   * when no path ended at an end step.
   */
  private JsonNode resultOf(WorkflowGraph graph, RunContext context) {
    List<WorkflowNode> ended = graph.nodes().stream()
        .filter(n -> n.type() == NodeType.END && context.toJson().path("nodes").has(n.id()))
        .toList();
    if (ended.size() == 1) return context.outputOf(ended.get(0).id());
    if (ended.size() > 1) {
      ObjectNode byEnd = Json.obj();
      ended.forEach(n -> byEnd.set(n.id(), context.outputOf(n.id())));
      return byEnd;
    }
    return context.last() == null ? Json.obj() : context.outputOf(context.last());
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
    RunContext context = RunContext.of(run.getContext());
    cancelChildren(context);
    cancelDeviceCommands(run.getId(), "The run stopped");
    cleanupBrowser(run, graphFor(workflows.findById(run.getWorkflowId())
        .orElseThrow(() -> new NotFoundException("Workflow", run.getWorkflowId())),
        run.getVersion()), context);
    save(run, context);
    wakeParent(context);
  }

  private void cleanupBrowser(WorkflowRunEntity run, WorkflowGraph graph, RunContext context) {
    java.util.List<RunContext.Browser> browsers = context.ownedBrowsers();
    if (browsers.isEmpty() || context.browserCleanupDone()
        || graph.trigger().config() != null
            && graph.trigger().config().path("keepBrowserOpen").asBoolean(false)) {
      return;
    }
    com.jizza.engine.api.device.DeviceCommands commands =
        deviceCommands == null ? null : deviceCommands.getIfAvailable();
    if (commands == null) return;
    context.recordBrowserCleanup();
    for (RunContext.Browser browser : browsers) {
      try {
        String cleanupId = java.util.UUID.nameUUIDFromBytes(
            (browser.deviceId() + ":" + browser.sessionId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        commands.submitSafetyCleanup(new com.jizza.engine.api.device.DeviceCommandRequest(
            run.getUserId(), browser.workspaceId(),
            com.jizza.engine.api.device.DeviceTarget.device(browser.deviceId()),
            "browser.close", 1,
            Json.obj().put("session", browser.sessionId()).put("what", "session"),
            java.time.Duration.ofSeconds(10), null,
            com.jizza.engine.api.device.DeviceCommands.SAFETY_CLEANUP_NODE,
            "workflow-browser-cleanup:" + run.getId() + ":" + cleanupId, false));
      } catch (RuntimeException e) {
        log.warn("Could not close a browser session of run {}", run.getId(), e);
      }
    }
  }

  /** Stops what this run still has waiting or running on paired devices. */
  private void cancelDeviceCommands(UUID runId, String reason) {
    com.jizza.engine.api.device.DeviceCommands commands =
        deviceCommands == null ? null : deviceCommands.getIfAvailable();
    if (commands == null) return;
    try {
      commands.cancelForRun(runId, reason);
    } catch (RuntimeException e) {
      log.warn("Could not cancel the device commands of run {}", runId, e);
    }
  }

  /** Stops every unfinished run this run started, and theirs in turn. */
  private void cancelChildren(RunContext context) {
    for (UUID childId : context.childRunIds()) {
      runs.findById(childId).ifPresent(child -> {
        if (child.getStatus() == RunStatus.DONE || child.getStatus() == RunStatus.FAILED
            || child.getStatus() == RunStatus.CANCELLED) {
          return;
        }
        child.setStatus(RunStatus.CANCELLED);
        child.setUpdatedAt(Instant.now());
        runs.save(child);
        audit.record(child.getUserId(), ActorType.SYSTEM, null, "run.cancelled",
            child.getId().toString(), Json.obj().put("reason", "its parent run stopped"));
        RunContext childContext = RunContext.of(child.getContext());
        cancelChildren(childContext);
        cancelDeviceCommands(child.getId(), "The parent run stopped");
        WorkflowEntity childWorkflow = workflows.findById(child.getWorkflowId()).orElse(null);
        if (childWorkflow != null) {
          cleanupBrowser(child, graphFor(childWorkflow, child.getVersion()), childContext);
          save(child, childContext);
        }
      });
    }
  }

  /**
   * A child run has finished: let the parent's waiting step pick up the result. A parent that is
   * running right now started this child itself and reads the result as soon as it returns.
   */
  private void wakeParent(RunContext context) {
    UUID parentId = context.parentRunId();
    if (parentId == null) return;
    runs.findById(parentId).ifPresent(parent -> {
      if (parent.getStatus() == RunStatus.WAITING
          || parent.getStatus() == RunStatus.PAUSED_WAITING_INSTRUCTION) {
        wake(parentId);
      }
    });
  }

  private void save(WorkflowRunEntity run, RunContext context) {
    run.setContext(context.toJson());
    run.setUpdatedAt(Instant.now());
    runs.save(run);
  }

  /** Upserts the per-node checkpoint. The unique (run_id, node_id) index keeps it to one row. */
  private void checkpoint(UUID runId, String nodeId, RunStepStatus status, JsonNode input,
                          JsonNode output, int attempts, String error, Instant startedAt) {
    WorkflowRunStepEntity step = steps.findByRunIdAndNodeId(runId, nodeId)
        .orElseGet(() -> {
          WorkflowRunStepEntity fresh = new WorkflowRunStepEntity();
          fresh.setRunId(runId);
          fresh.setNodeId(nodeId);
          // The first attempt's start, so a retried step's time covers every attempt.
          fresh.setStartedAt(startedAt);
          return fresh;
        });
    step.setStatus(status);
    step.setInput(input);
    step.setOutput(output);
    step.setAttempts(attempts);
    step.setError(error);
    // For a failed step this is when its latest attempt gave up, so the history can time it.
    step.setFinishedAt(Instant.now());
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
