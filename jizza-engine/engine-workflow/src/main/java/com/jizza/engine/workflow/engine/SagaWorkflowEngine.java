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
import java.time.Duration;
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
 * exactly once and a branch that merges back never waits for the side it did not take. Steps that
 * wait on another system (HTTP, connector, MCP, agent and script steps) start on their own threads
 * as soon as they are ready, so parallel paths really run at the same time; their results are
 * applied back on the run's own worker, which keeps one writer and one transaction per run. A path
 * waiting on a person, a timer, a device or child runs is parked and holds up nobody.
 *
 * <p><b>Join timeouts.</b> A join's timer starts when the first path reaches it (an hour unless
 * the step says otherwise). When it runs out the join either carries on with the paths that made
 * it, dropping the ones still on their way, or fails the run.
 */
@Service
public class SagaWorkflowEngine implements WorkflowEngine,
    org.springframework.beans.factory.DisposableBean {

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

  /**
   * Steps that start on their own thread when they are ready, so parallel paths run at the same
   * time. They spend their time waiting on another system (a URL, a connector, an MCP server, a
   * model) and only read the run's state, which is why they can work from a copy of it. Every
   * other step runs on the run's own worker: it is quick, or it changes the run's state (variables,
   * a step's own bookkeeping, the browser it holds), or it starts other runs that report back to
   * this one. Device actions are already asynchronous: they hand the command over and park.
   */
  static final Set<NodeType> RUN_ALONGSIDE = Set.of(NodeType.HTTP_REQUEST,
      NodeType.CONNECTOR_TOOL, NodeType.MCP_TOOL, NodeType.AGENT_STEP, NodeType.SCRIPT);

  /** Threads for steps running alongside others; virtual, so a waiting step costs next to nothing. */
  private final java.util.concurrent.ExecutorService stepThreads =
      java.util.concurrent.Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("workflow-step-", 0).factory());

  /** At most this many steps of one run at once; 1 runs every step on the run's worker. */
  private int maxConcurrentSteps = 8;

  /** How long a join waits after its first path arrives when the step sets nothing. */
  private Duration defaultJoinTimeout = Duration.ofHours(1);

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
  private org.springframework.context.ApplicationEventPublisher events;

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

  /** How many steps of one run may run at once ({@code jizza.workflow.parallel.max-concurrent-steps}). */
  @Autowired(required = false)
  public void setMaxConcurrentSteps(
      @org.springframework.beans.factory.annotation.Value(
          "${jizza.workflow.parallel.max-concurrent-steps:8}") int maxConcurrentSteps) {
    this.maxConcurrentSteps = Math.max(1, maxConcurrentSteps);
  }

  /**
   * How long a join waits after its first path arrives when the step does not say
   * ({@code jizza.workflow.join.default-timeout}); zero waits for ever.
   */
  @Autowired(required = false)
  public void setDefaultJoinTimeout(
      @org.springframework.beans.factory.annotation.Value(
          "${jizza.workflow.join.default-timeout:PT1H}") Duration defaultJoinTimeout) {
    this.defaultJoinTimeout = defaultJoinTimeout;
  }

  @Override
  public void destroy() {
    stepThreads.shutdownNow();
  }

  /** Paired devices, when the app has them, so a stopped run stops its device commands too. */
  @Autowired(required = false)
  public void setDeviceCommands(
      ObjectProvider<com.jizza.engine.api.device.DeviceCommands> deviceCommands) {
    this.deviceCommands = deviceCommands;
  }

  /** Where a failed run is announced, so the person's phone can alert them. */
  @Autowired(required = false)
  public void setEvents(org.springframework.context.ApplicationEventPublisher events) {
    this.events = events;
  }

  @Override
  @Transactional
  public RunDto start(UUID workflowId, int version, JsonNode input, String triggerSource) {
    WorkflowEntity workflow = workflows.findById(workflowId)
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
    return launch(workflow, version, input, triggerSource, context -> { });
  }

  @Override
  @Transactional
  public RunDto start(UUID workflowId, int version, JsonNode input, String triggerSource,
                      JsonNode stepOverrides) {
    WorkflowEntity workflow = workflows.findById(workflowId)
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
    return launch(workflow, version, input, triggerSource,
        context -> context.setStepOverrides(stepOverrides));
  }

  @Override
  @Transactional
  public RunDto start(UUID workflowId, int version, JsonNode input, String triggerSource,
                      JsonNode stepOverrides, UUID preferredBrowser) {
    WorkflowEntity workflow = workflows.findById(workflowId)
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
    return launch(workflow, version, input, triggerSource, context -> {
      context.setStepOverrides(stepOverrides);
      context.preferBrowser(preferredBrowser);
    });
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

  /**
   * A run nobody is watching that the person allowed anyway: of the current version of a workflow
   * they turned on with these steps, or a library run of steps they allowed unattended.
   */
  private static boolean allowedUnattended(WorkflowEntity workflow, int version, String triggerSource) {
    if (version == workflow.getVersion() && workflow.isEnabled()) return true;
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
      if (instruction != null && instruction.path("inputs").isObject()) {
        // What the person set on the approval card; the step applies only fields it offered.
        context.recordApprovedInputs(node, instruction.get("inputs"));
      }
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

    FailedStepChoice choice = FailedStepChoice.of(text);
    if (choice != FailedStepChoice.STOP && choice != FailedStepChoice.SKIP
        && choice != FailedStepChoice.RETRY_CHECKED && stepFailed(runId, node)
        && FailedStepChoice.irreversible(configOf(run, node))) {
      // Paying, booking, placing an order: trying again blind could do it twice.
      throw new ValidationException("Step '" + labelOf(run, node) + "' may already have gone "
          + "through. Check the site first (your orders or bookings), then answer \""
          + FailedStepChoice.RETRY_CHECKED.answer() + "\" to try it again, or skip or stop.");
    }
    context.recordInstruction(node, text);
    clearPause(context, node);
    // Answered here or from the inbox: either way nothing else should still ask about this step.
    closeQuestions(runId, node, "Closed: you answered this from the run");

    if (choice == FailedStepChoice.STOP) {
      context.dequeue(node);
      run.setContext(context.toJson());
      audit.record(run.getUserId(), ActorType.USER, null, "run.instruction_submitted",
          run.getId().toString(), Json.obj().put("node", node).put("choice", "stop"));
      stop(run, "Stopped by you at step '" + labelOf(run, node) + "'");
      return toDto(run);
    }
    if (choice == FailedStepChoice.SKIP && stepFailed(runId, node)) {
      return skipStep(run, context, node);
    }

    forgetFailedChildren(context, node);
    run.setContext(context.toJson());
    run.setStatus(RunStatus.RUNNING);
    run.setCurrentNode(node);
    runs.save(run);

    audit.record(run.getUserId(), ActorType.USER, null, "run.instruction_submitted",
        run.getId().toString(), Json.obj().put("node", node));
    return advance(runId);
  }

  private boolean stepFailed(UUID runId, String nodeId) {
    return steps.findByRunIdAndNodeId(runId, nodeId)
        .map(s -> s.getStatus() == RunStepStatus.FAILED).orElse(false);
  }

  /**
   * The person chose to carry on without a step that failed: it is marked skipped with no output,
   * and the run goes on down every path after it, as if it had finished.
   */
  private RunDto skipStep(WorkflowRunEntity run, RunContext context, String nodeId) {
    WorkflowGraph graph = graphFor(workflows.findById(run.getWorkflowId())
        .orElseThrow(() -> new NotFoundException("Workflow", run.getWorkflowId())),
        run.getVersion());
    WorkflowNode node = graph.node(nodeId)
        .orElseThrow(() -> new ValidationException("Step '" + nodeId + "' is not in this run"));
    int attempts = steps.findByRunIdAndNodeId(run.getId(), nodeId)
        .map(WorkflowRunStepEntity::getAttempts).orElse(0);
    context.recordOutput(nodeId, Json.obj());
    checkpoint(run.getId(), nodeId, RunStepStatus.SKIPPED, null, Json.obj(), attempts,
        "Skipped by you after it failed", Instant.now());
    run.setStatus(RunStatus.RUNNING);
    run.setCurrentNode(nodeId);
    finishNode(run, graph, node, context);
    audit.record(run.getUserId(), ActorType.USER, null, "run.step_skipped",
        run.getId().toString(), Json.obj().put("node", nodeId));
    return advance(run.getId());
  }

  private JsonNode configOf(WorkflowRunEntity run, String nodeId) {
    return workflows.findById(run.getWorkflowId())
        .map(w -> graphFor(w, run.getVersion()))
        .flatMap(g -> g.node(nodeId))
        .map(WorkflowNode::config)
        .orElse(null);
  }

  private String labelOf(WorkflowRunEntity run, String nodeId) {
    return workflows.findById(run.getWorkflowId())
        .map(w -> graphFor(w, run.getVersion()))
        .flatMap(g -> g.node(nodeId))
        .map(n -> n.label() == null ? n.id() : n.label())
        .orElse(nodeId);
  }

  /** Closes the inbox items that ask about this run (or one step of it), if there is an inbox. */
  private void closeQuestions(UUID runId, String nodeId, String reason) {
    NotificationBus bus = notifications.getIfAvailable();
    if (bus == null) return;
    try {
      bus.closeForRun(runId, nodeId, reason);
    } catch (RuntimeException e) {
      log.warn("Could not close the questions of run {}", runId, e);
    }
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
    if (named != null && context.abandonedBy(named) != null) {
      throw new ValidationException("That step is no longer waiting: the join after it stopped "
          + "waiting for it and the run went on without it");
    }
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
    // A join whose timer ran out while the run slept stops waiting; the advance drops the rest.
    if (context.joinDeadlines().values().stream().anyMatch(d -> !now.isBefore(d))) due = true;
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
    if (run.getStatus() == RunStatus.DONE || run.getStatus() == RunStatus.FAILED
        || run.getStatus() == RunStatus.CANCELLED) {
      // Already over; still tidy away anything that asks about it.
      closeQuestions(runId, null, "Closed: the run had already ended");
      return;
    }
    stop(run, "Stopped by you");
  }

  /** Ends a run the person stopped: its devices, browser and child runs are let go too. */
  private void stop(WorkflowRunEntity run, String why) {
    UUID runId = run.getId();
    run.setStatus(RunStatus.CANCELLED);
    run.setError(why);
    run.setUpdatedAt(Instant.now());
    runs.save(run);
    audit.record(run.getUserId(), ActorType.USER, null, "run.cancelled", runId.toString(),
        Json.obj().put("reason", why));
    closeQuestions(runId, null, "Closed: the run was stopped");
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
   *
   * <p>Steps that spend their time waiting on someone else ({@link #RUN_ALONGSIDE}) are started on
   * their own threads, up to {@code maxConcurrentSteps} at once, so parallel paths really do run
   * at the same time. Their results come back here and are applied one by one; nothing but the
   * step's executor ever runs off this thread.
   */
  private RunDto advance(UUID runId) {
    ConcurrentSteps alongside = new ConcurrentSteps(stepThreads);
    boolean finished = false;
    try {
      RunDto result = drive(runId, alongside);
      finished = true;
      return result;
    } finally {
      if (!finished && !alongside.isEmpty()) {
        // Something went wrong on this thread; still wait for the steps it started, so none is
        // left running with nobody to record what it did.
        try {
          stop(runId, alongside);
        } catch (RuntimeException e) {
          log.warn("Run {}: could not record the steps still running alongside", runId, e);
        }
      }
    }
  }

  /** How a failed attempt that may be retried is retried. */
  private enum Retry {
    /** Back off on this thread, then the loop picks the step again (steps run here). */
    HERE,
    /** Start the next attempt on its own thread, after the back-off. */
    ALONGSIDE,
    /** Leave the step on the ready list for the run's next advance (the run is stopping). */
    LATER
  }

  private RunDto drive(UUID runId, ConcurrentSteps alongside) {
    int executed = 0;
    while (true) {
      WorkflowRunEntity run = requireRun(runId);
      if (run.getStatus() != RunStatus.RUNNING) return stop(runId, alongside);

      // Checked here, between nodes: the kill switch halts autonomy at the next safe checkpoint
      // rather than tearing down whatever is mid-flight (R40).
      try {
        killSwitch.requireActive(run.getUserId());
      } catch (AutonomyDeniedException e) {
        log.info("Run {} halted at node {} because autonomy is paused", runId, run.getCurrentNode());
        audit.record(run.getUserId(), ActorType.SYSTEM, null, "run.halted_by_kill_switch",
            runId.toString(), Json.obj().put("node", String.valueOf(run.getCurrentNode())));
        return stop(runId, alongside);
      }

      WorkflowGraph graph = graphFor(workflows.findById(run.getWorkflowId())
          .orElseThrow(() -> new NotFoundException("Workflow", run.getWorkflowId())),
          run.getVersion());
      RunContext context = RunContext.of(run.getContext());
      if (!context.hasFrontier()) {
        // Started before runs tracked a frontier: carry on from the one node it was on.
        context.startFrontier(run.getCurrentNode(), true);
      }

      // A step running alongside has finished: apply it first, so the paths it opens can start.
      ConcurrentSteps.Result done = poll(alongside, 0);
      if (done != null) {
        if (applyAlongside(run, graph, context, done, alongside, Retry.ALONGSIDE)) {
          return stop(runId, alongside);
        }
        continue;
      }

      String nodeId = nextToRun(graph, context, alongside);
      if (nodeId == null) {
        // Nothing else can start, so this is the moment to see whether a join has waited long
        // enough. Checked after the ready steps, so a path arriving at the deadline still counts.
        JoinTimeouts timeouts = timeOutDueJoins(run, graph, context);
        if (timeouts == JoinTimeouts.ENDED) return stop(runId, alongside);
        if (timeouts == JoinTimeouts.CHANGED) continue;
        if (alongside.isEmpty()) return settle(run, graph, context);
        // Everything that can start has started: wait for one to finish, or for a join's timer.
        done = poll(alongside, millisUntilNextJoinDeadline(context));
        if (done != null && applyAlongside(run, graph, context, done, alongside, Retry.ALONGSIDE)) {
          return stop(runId, alongside);
        }
        continue;
      }

      if (executed++ >= MAX_NODES_PER_ADVANCE) {
        log.error("Run {} exceeded {} nodes in one advance", runId, MAX_NODES_PER_ADVANCE);
        failRun(run, run.getCurrentNode(), "The run executed too many nodes and was stopped");
        return stop(runId, alongside);
      }
      run.setCurrentNode(nodeId);

      WorkflowNode node = graph.node(nodeId).orElse(null);
      if (node == null) {
        failRun(run, nodeId, "The run points at node '" + nodeId + "', which is not in this "
            + "version of the workflow");
        return stop(runId, alongside);
      }
      node = StepOverrides.apply(node, context.stepOverrides());

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
        return stop(runId, alongside);
      }

      int attempt = checkpoint.map(WorkflowRunStepEntity::getAttempts).orElse(0) + 1;
      if (runsAlongside(node)) {
        startAlongside(run, graph, node, context, attempt, 0L, alongside);
        continue;
      }

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
      if (current.getStatus() != RunStatus.RUNNING) return stop(runId, alongside);

      if (applyOutcome(current, graph, node, context, outcome, attempt, stepInput, stepStarted,
          Retry.HERE, alongside)) {
        return stop(runId, alongside);
      }
    }
  }

  /**
   * The first ready step that is not already running. A step that would run alongside others
   * waits its turn while {@code maxConcurrentSteps} are already running.
   */
  private String nextToRun(WorkflowGraph graph, RunContext context, ConcurrentSteps alongside) {
    boolean full = alongside.size() >= maxConcurrentSteps;
    for (String id : context.ready()) {
      if (alongside.running(id)) continue;
      if (full && graph.node(id).map(this::runsAlongside).orElse(false)) continue;
      return id;
    }
    return null;
  }

  private boolean runsAlongside(WorkflowNode node) {
    return maxConcurrentSteps > 1 && RUN_ALONGSIDE.contains(node.type());
  }

  /** Starts one attempt of {@code node} on its own thread, from a copy of the run's state. */
  private void startAlongside(WorkflowRunEntity run, WorkflowGraph graph, WorkflowNode node,
                              RunContext context, int attempt, long delayMillis,
                              ConcurrentSteps alongside) {
    NodeExecutor executor = executors.get(node.type());
    // The step reads its own copy: the run's state keeps changing here while it runs.
    RunContext copy = RunContext.of(context.toJson());
    JsonNode stepInput = stepInputs.snapshot(node, copy);
    NodeExecutor.Execution execution = new NodeExecutor.Execution(run.getId(), run.getUserId(),
        owningAgentOf(run), node, copy, attempt, run.getWorkflowId(), run.getVersion(), graph);
    UUID runId = run.getId();
    alongside.submit(node.id(), attempt, stepInput, delayMillis, () -> {
      try {
        return executor.execute(execution);
      } catch (RuntimeException e) {
        log.warn("Node {} of run {} threw", node.id(), runId, e);
        return NodeExecutor.Outcome.retryable(String.valueOf(e.getMessage()));
      }
    });
  }

  /** Applies a step that ran alongside. @return true when the run has ended */
  private boolean applyAlongside(WorkflowRunEntity run, WorkflowGraph graph, RunContext context,
                                 ConcurrentSteps.Result done, ConcurrentSteps alongside,
                                 Retry retry) {
    WorkflowNode node = graph.node(done.nodeId()).orElse(null);
    if (node == null) return false;
    NodeExecutor.Outcome outcome = done.outcome();
    boolean dropped = context.abandonedBy(node.id()) != null;
    if (dropped || run.getStatus() != RunStatus.RUNNING) {
      // A join stopped waiting for it, or the run has ended: keep what it produced for the
      // history, and go no further down its path.
      if (outcome.status() == NodeExecutor.Outcome.Status.COMPLETED) {
        context.recordOutput(node.id(), outcome.output());
        checkpoint(run.getId(), node.id(), RunStepStatus.DONE, done.input(), outcome.output(),
            done.attempt(), dropped ? "Finished after the join stopped waiting for it" : null,
            done.started());
        save(run, context);
      }
      return false;
    }
    return applyOutcome(run, graph, node, context, outcome, done.attempt(), done.input(),
        done.started(), retry, alongside);
  }

  /** What a step's outcome does to the run. @return true when the run has ended */
  private boolean applyOutcome(WorkflowRunEntity current, WorkflowGraph graph, WorkflowNode node,
                               RunContext context, NodeExecutor.Outcome outcome, int attempt,
                               JsonNode stepInput, Instant stepStarted, Retry retry,
                               ConcurrentSteps alongside) {
    UUID runId = current.getId();
    String nodeId = node.id();
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
          outcome.message(), NotificationKind.PERMISSION_REQUEST, outcome.wakeAt(), null,
          outcome.output());
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
        return handleExhausted(current, node, context, outcome.message());
      }
      case RETRYABLE -> {
        int maxAttempts = node.retryOrDefault().maxAttempts();
        checkpoint(runId, nodeId, RunStepStatus.FAILED, stepInput, null, attempt,
            outcome.message(), stepStarted);
        if (attempt >= maxAttempts) {
          return handleExhausted(current, node, context,
              outcome.message() + " (after " + attempt + " attempts)");
        }
        save(current, context);
        // The attempt count lives in the checkpoint, so a restart mid-retry does not reset it.
        switch (retry) {
          case HERE -> backoff(node, attempt);  // the loop picks the same node again
          case ALONGSIDE -> startAlongside(current, graph, node, context, attempt + 1,
              backoffMillis(node, attempt), alongside);
          case LATER -> { }
        }
      }
    }
    return false;
  }

  /**
   * The run is stopping here: wait for the steps still running alongside and record what they
   * did, without starting anything new, then report the run as it now stands.
   */
  private RunDto stop(UUID runId, ConcurrentSteps alongside) {
    while (!alongside.isEmpty()) {
      ConcurrentSteps.Result done = poll(alongside, 1000);
      if (done == null) continue;
      WorkflowRunEntity run = requireRun(runId);
      WorkflowGraph graph = graphFor(workflows.findById(run.getWorkflowId())
          .orElseThrow(() -> new NotFoundException("Workflow", run.getWorkflowId())),
          run.getVersion());
      applyAlongside(run, graph, RunContext.of(run.getContext()), done, alongside, Retry.LATER);
    }
    return toDto(requireRun(runId));
  }

  private ConcurrentSteps.Result poll(ConcurrentSteps alongside, long millis) {
    if (alongside.isEmpty()) return null;
    try {
      return alongside.poll(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while steps were running", e);
    }
  }

  // ── join timeouts ───────────────────────────────────────────────────────────

  private enum JoinTimeouts { NONE, CHANGED, ENDED }

  /** How long a join waits after its first path arrives; null when it waits for ever. */
  Duration joinTimeout(WorkflowNode join) {
    JsonNode value = join.config() == null ? null : join.config().get("timeout");
    if (value != null && value.isTextual() && "none".equals(value.asText())) return null;
    Duration fallback = defaultJoinTimeout == null || defaultJoinTimeout.isZero()
        || defaultJoinTimeout.isNegative() ? null : defaultJoinTimeout;
    if (value == null || value.isNull() || value.asText().isBlank()) return fallback;
    try {
      Duration parsed = Duration.parse(value.asText());
      return parsed.isZero() || parsed.isNegative() ? fallback : parsed;
    } catch (java.time.format.DateTimeParseException e) {
      return fallback;
    }
  }

  /** The first path has reached a join that still waits for others: start its timer. */
  private void startJoinTimer(WorkflowGraph graph, RunContext context, String to) {
    if (context.joinDeadline(to) != null) return;
    WorkflowNode join = graph.node(to).orElse(null);
    if (join == null || join.type() != NodeType.JOIN) return;
    Duration timeout = joinTimeout(join);
    if (timeout != null) context.startJoinTimer(to, clock.instant().plus(timeout));
  }

  private long millisUntilNextJoinDeadline(RunContext context) {
    Instant now = clock.instant();
    long soonest = context.joinDeadlines().values().stream()
        .mapToLong(d -> Math.max(1, Duration.between(now, d).toMillis()))
        .min().orElse(1000);
    // Capped, so a moved clock (or a run stopped from elsewhere) is noticed soon enough.
    return Math.min(soonest, 1000);
  }

  /**
   * Joins whose timer has run out stop waiting. With {@code onTimeout: "fail"} the run fails;
   * otherwise the join carries on with the paths that reached it, and the steps still running or
   * waiting on the way to it are dropped (their questions, child runs and device commands too).
   */
  private JoinTimeouts timeOutDueJoins(WorkflowRunEntity run, WorkflowGraph graph,
                                       RunContext context) {
    Map<String, Instant> deadlines = context.joinDeadlines();
    if (deadlines.isEmpty()) return JoinTimeouts.NONE;
    Instant now = clock.instant();
    boolean changed = false;
    for (Map.Entry<String, Instant> entry : deadlines.entrySet()) {
      if (now.isBefore(entry.getValue())) continue;
      String joinId = entry.getKey();
      context.stopJoinTimer(joinId);
      changed = true;
      WorkflowNode join = graph.node(joinId).orElse(null);
      if (join == null || context.ready().contains(joinId)
          || steps.findByRunIdAndNodeId(run.getId(), joinId).isPresent()) {
        continue;
      }
      Map<String, Boolean> arrived = context.arrivals(joinId);
      List<String> missing = graph.incoming(joinId).stream().map(WorkflowEdge::from).distinct()
          .filter(from -> !arrived.containsKey(from)).toList();
      String label = join.label() == null ? joinId : join.label();
      Duration timeout = joinTimeout(join);
      String waited = timeout == null ? "its timeout" : describe(timeout);
      ArrayNode missingJson = Json.mapper().createArrayNode();
      missing.forEach(missingJson::add);
      audit.record(run.getUserId(), ActorType.SYSTEM, owningAgentOf(run), "run.join_timed_out",
          run.getId().toString(), Json.obj().put("node", joinId).set("missing", missingJson));
      boolean fail = join.config() != null
          && "fail".equals(join.config().path("onTimeout").asText());
      if (fail) {
        run.setContext(context.toJson());
        failRun(run, joinId, "Join '" + label + "' stopped waiting after " + waited
            + "; these steps had not finished: " + String.join(", ", missing));
        return JoinTimeouts.ENDED;
      }
      log.info("Run {}: join {} stopped waiting after {} for {}", run.getId(), joinId, waited,
          missing);
      dropUpstream(run, graph, context, joinId, label, waited);
      for (String from : missing) context.arrive(joinId, from, false);
      context.state(joinId).set("timedOut", missingJson);
      context.enqueue(joinId);
    }
    if (!changed) return JoinTimeouts.NONE;
    save(run, context);
    return JoinTimeouts.CHANGED;
  }

  /** Drops what is still running or waiting on the way to a join that stopped waiting. */
  private void dropUpstream(WorkflowRunEntity run, WorkflowGraph graph, RunContext context,
                            String joinId, String label, String waited) {
    Set<String> upstream = new HashSet<>();
    java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(joinId));
    while (!queue.isEmpty()) {
      for (WorkflowEdge edge : graph.incoming(queue.poll())) {
        if (upstream.add(edge.from())) queue.add(edge.from());
      }
    }
    String why = "Dropped: join '" + label + "' stopped waiting after " + waited;
    for (String id : context.ready()) {
      if (!upstream.contains(id)) continue;
      context.dequeue(id);
      context.abandon(id, joinId);
      dropCheckpoint(run.getId(), id, why);
    }
    for (String id : context.parked().keySet()) {
      if (!upstream.contains(id)) continue;
      context.forgetParked(id);
      if (id.equals(context.pausedNode())) context.clearPause();
      context.abandon(id, joinId);
      stopStepWork(run.getId(), context, id);
      dropCheckpoint(run.getId(), id, why);
    }
  }

  private void dropCheckpoint(UUID runId, String nodeId, String why) {
    int attempts = steps.findByRunIdAndNodeId(runId, nodeId)
        .map(WorkflowRunStepEntity::getAttempts).orElse(0);
    checkpoint(runId, nodeId, RunStepStatus.SKIPPED, null, null, attempts, why, Instant.now());
  }

  /** Stops the child runs and the device command a dropped step started. */
  private void stopStepWork(UUID runId, RunContext context, String nodeId) {
    JsonNode state = context.peekState(nodeId);
    for (JsonNode child : state.path("children")) {
      if (!child.path("runId").isTextual()) continue;
      runs.findById(UUID.fromString(child.get("runId").asText())).ifPresent(childRun -> {
        if (childRun.getStatus() == RunStatus.DONE || childRun.getStatus() == RunStatus.FAILED
            || childRun.getStatus() == RunStatus.CANCELLED) {
          return;
        }
        cancelChild(childRun, "the join waiting for it stopped waiting");
      });
    }
    com.jizza.engine.api.device.DeviceCommands commands =
        deviceCommands == null ? null : deviceCommands.getIfAvailable();
    if (commands != null && state.path("commandId").isTextual()) {
      try {
        commands.cancel(UUID.fromString(state.get("commandId").asText()),
            "The join waiting for this step stopped waiting");
      } catch (RuntimeException e) {
        log.warn("Run {}: could not cancel the device command of step {}", runId, nodeId, e);
      }
    }
  }

  private static String describe(Duration duration) {
    long minutes = duration.toMinutes();
    if (minutes == 0) return duration.toSeconds() + " seconds";
    if (minutes % (60 * 24) == 0) return plural(minutes / (60 * 24), "day");
    if (minutes % 60 == 0) return plural(minutes / 60, "hour");
    return plural(minutes, "minute");
  }

  private static String plural(long n, String unit) { return n + " " + unit + (n == 1 ? "" : "s"); }

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
    if (!allResolved) {
      // A join's timer starts when the first path actually reaches it.
      if (live) startJoinTimer(graph, context, to);
      return;
    }
    context.stopJoinTimer(to);
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
      Instant soonest = java.util.stream.Stream.concat(
              parked.keySet().stream().map(context::wakeAt),
              context.joinDeadlines().values().stream())
          .filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
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
    parkForPerson(run, context, nodeId, reason, prompt, kind, until, null, null);
  }

  private void parkForPerson(WorkflowRunEntity run, RunContext context, String nodeId,
                             String reason, String prompt, NotificationKind kind, Instant until,
                             FailedStepChoice ifNoAnswer) {
    parkForPerson(run, context, nodeId, reason, prompt, kind, until, ifNoAnswer, null);
  }

  /**
   * As above. A step that failed for good also lists the answers that act on the run by
   * themselves (try again, skip this step, stop the run), so the inbox can offer them as buttons,
   * and the one applied if nobody answers before the question expires.
   *
   * @param ifNoAnswer non-null for a step that failed for good
   * @param extra      what the step adds to the question, such as its values to check
   */
  private void parkForPerson(WorkflowRunEntity run, RunContext context, String nodeId,
                             String reason, String prompt, NotificationKind kind, Instant until,
                             FailedStepChoice ifNoAnswer, JsonNode extra) {
    context.park(nodeId, reason, until);
    save(run, context);

    NotificationBus bus = notifications.getIfAvailable();
    if (bus != null) {
      bus.raise(kind, owningAgentOf(run), NotificationContext.forRun(run.getUserId(),
          prompt == null ? "This run needs your input to continue." : prompt,
          detailFor(run, nodeId, ifNoAnswer, extra), run.getId()));
    } else {
      log.warn("Run {} paused at {} but the notification bus is unavailable, so nobody was told",
          run.getId(), nodeId);
    }
    audit.record(run.getUserId(), ActorType.SYSTEM, owningAgentOf(run), "run.paused",
        run.getId().toString(), Json.obj().put("node", nodeId).put("reason", reason));
  }

  private ObjectNode detailFor(WorkflowRunEntity run, String nodeId, FailedStepChoice ifNoAnswer,
                               JsonNode extra) {
    ObjectNode detail = Json.obj();
    if (extra != null && extra.isObject()) detail.setAll((ObjectNode) extra.deepCopy());
    detail.put("node", nodeId).put("workflowId", run.getWorkflowId().toString());
    if (ifNoAnswer != null) {
      detail.put("failedStep", true);
      ArrayNode choices = detail.putArray("choices");
      boolean irreversible = extra != null && extra.path("irreversible").asBoolean(false);
      for (FailedStepChoice choice : FailedStepChoice.offered(
          irreversible ? Json.obj().put("irreversible", true) : null)) {
        choices.add(choice.answer());
      }
      // The inbox applies this answer when the question expires unanswered.
      detail.put("defaultChoice", ifNoAnswer.answer());
    }
    return detail;
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
    boolean irreversible = FailedStepChoice.irreversible(node.config());
    parkForPerson(run, context, node.id(), PAUSED_FOR_INSTRUCTION,
        "Step '" + (node.label() == null ? node.id() : node.label()) + "' could not complete: "
            + message + (irreversible
                ? " It cannot be undone and may have gone through already: check the site (your"
                    + " orders or bookings) before you try it again."
                : "") + " How would you like to proceed?",
        NotificationKind.INSTRUCTION_REQUIRED, null, FailedStepChoice.ifNoAnswer(node.config()),
        irreversible ? Json.obj().put("irreversible", true) : null);
    return false;
  }

  private RunDto completeRun(WorkflowRunEntity run, WorkflowGraph graph, RunContext context) {
    context.recordResult(resultOf(graph, context));
    // A finished run leaves its browser as its steps left it: closing is a "Close browser" step
    // (new recordings end with one), so removing that step keeps the page open. A run that fails
    // or is cancelled still closes what it opened, because no step got the chance to.
    run.setContext(context.toJson());
    run.setStatus(RunStatus.DONE);
    run.setCurrentNode(null);
    run.setUpdatedAt(Instant.now());
    runs.save(run);
    audit.record(run.getUserId(), ActorType.SYSTEM, owningAgentOf(run), "run.completed",
        run.getId().toString(), null);
    closeQuestions(run.getId(), null, "Closed: the run finished");
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
    closeQuestions(run.getId(), null, "Closed: the run failed");
    RunContext context = RunContext.of(run.getContext());
    cancelChildren(context);
    cancelDeviceCommands(run.getId(), "The run stopped");
    WorkflowEntity workflow = workflows.findById(run.getWorkflowId())
        .orElseThrow(() -> new NotFoundException("Workflow", run.getWorkflowId()));
    cleanupBrowser(run, graphFor(workflow, run.getVersion()), context);
    save(run, context);
    wakeParent(context);
    // A child's failure reaches the person through its parent, so only top-level runs alert.
    if (events != null && context.parentRunId() == null) {
      events.publishEvent(new com.jizza.engine.api.event.RunFailed(run.getUserId(), run.getId(),
          workflow.getId(), workflow.getName(), message));
    }
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
        cancelChild(child, "its parent run stopped");
      });
    }
  }

  /** Cancels one unfinished child run, and everything it started in turn. */
  private void cancelChild(WorkflowRunEntity child, String reason) {
    child.setStatus(RunStatus.CANCELLED);
    child.setUpdatedAt(Instant.now());
    runs.save(child);
    audit.record(child.getUserId(), ActorType.SYSTEM, null, "run.cancelled",
        child.getId().toString(), Json.obj().put("reason", reason));
    RunContext childContext = RunContext.of(child.getContext());
    cancelChildren(childContext);
    cancelDeviceCommands(child.getId(), "The parent run stopped");
    WorkflowEntity childWorkflow = workflows.findById(child.getWorkflowId()).orElse(null);
    if (childWorkflow != null) {
      cleanupBrowser(child, graphFor(childWorkflow, child.getVersion()), childContext);
      save(child, childContext);
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

  private static long backoffMillis(WorkflowNode node, int attempt) {
    var policy = node.retryOrDefault();
    long delay = (long) (policy.backoffMillis() * Math.pow(policy.multiplier(), attempt - 1.0));
    return Math.min(delay, policy.timeoutMillis());
  }

  private void backoff(WorkflowNode node, int attempt) {
    try {
      Thread.sleep(backoffMillis(node, attempt));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private UUID owningAgentOf(WorkflowRunEntity run) {
    return workflows.findById(run.getWorkflowId())
        .map(WorkflowEntity::getActingAgentId).orElse(null);
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
