package com.jizza.workflow.engine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.audit.AuditLog;
import com.jizza.common.autonomy.AutonomyGate;
import com.jizza.common.autonomy.Decision;
import com.jizza.common.autonomy.KillSwitch;
import com.jizza.common.connector.IdempotencyKey;
import com.jizza.common.connector.ToolExecutor;
import com.jizza.common.dto.GeneratedToolDto;
import com.jizza.common.dto.NotificationDto;
import com.jizza.common.enums.AutonomyRule;
import com.jizza.common.enums.EscalationState;
import com.jizza.common.enums.NodeType;
import com.jizza.common.enums.NotificationKind;
import com.jizza.common.enums.NotificationStatus;
import com.jizza.common.enums.WorkflowOrigin;
import com.jizza.common.enums.WorkflowStatus;
import com.jizza.common.error.AutonomyDeniedException;
import com.jizza.common.notification.Answer;
import com.jizza.common.notification.NotificationBus;
import com.jizza.common.notification.NotificationContext;
import com.jizza.common.tool.ToolResult;
import com.jizza.common.util.Json;
import com.jizza.common.workflow.graph.WorkflowGraph;
import com.jizza.persistence.entity.GeneratedToolEntity;
import com.jizza.persistence.entity.WorkflowEntity;
import com.jizza.persistence.entity.WorkflowRunEntity;
import com.jizza.persistence.entity.WorkflowRunStepEntity;
import com.jizza.persistence.repository.GeneratedToolRepository;
import com.jizza.persistence.repository.WorkflowRepository;
import com.jizza.persistence.repository.WorkflowRunRepository;
import com.jizza.persistence.repository.WorkflowRunStepRepository;
import com.jizza.persistence.repository.WorkflowVersionRepository;
import com.jizza.workflow.nodes.BranchNodeExecutor;
import com.jizza.workflow.nodes.ConnectorToolNodeExecutor;
import com.jizza.workflow.nodes.EndNodeExecutor;
import com.jizza.workflow.nodes.InterventionNodeExecutor;
import com.jizza.workflow.nodes.TransformNodeExecutor;
import com.jizza.workflow.nodes.TriggerNodeExecutor;
import com.jizza.workflow.sandbox.JexlSandbox;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * In-memory stand-in for the run tables, so the engine's durability behaviour can be exercised
 * without a database. The rows are real objects that survive between calls, which is exactly the
 * property the resume tests need.
 */
public final class EngineHarness {

  public final UUID userId = UUID.randomUUID();
  public final UUID agentId = UUID.randomUUID();

  public final List<WorkflowRunEntity> runRows = new ArrayList<>();
  public final List<WorkflowRunStepEntity> stepRows = new ArrayList<>();
  public final List<NotificationDto> raised = new ArrayList<>();

  public final WorkflowEntity workflow = new WorkflowEntity();
  public final GeneratedToolEntity sendEmailTool = new GeneratedToolEntity();

  /** Counts real invocations of the side-effecting tool, which is what exactly-once is about. */
  public int toolInvocations;
  public ToolResult nextToolResult = ToolResult.ok(Json.obj().put("sent", true));
  public boolean paused;
  public AutonomyRule toolRule = AutonomyRule.ALLOW;

  public final SagaWorkflowEngine engine;
  public final WorkflowRunRepository runs;
  public final WorkflowRunStepRepository steps;

  @SuppressWarnings("unchecked")
  public EngineHarness(WorkflowGraph graph) {
    workflow.setId(UUID.randomUUID());
    workflow.setUserId(userId);
    workflow.setName("Test workflow");
    workflow.setGraph(graph.toJson());
    workflow.setGraphFingerprint(graph.fingerprint());
    workflow.setOrigin(WorkflowOrigin.USER_BUILT);
    workflow.setStatus(WorkflowStatus.APPROVED);
    workflow.setOwningAgentId(agentId);
    workflow.setVersion(1);

    WorkflowRepository workflows = mock(WorkflowRepository.class);
    when(workflows.findById(workflow.getId())).thenReturn(Optional.of(workflow));

    runs = mock(WorkflowRunRepository.class);
    when(runs.save(any())).thenAnswer(inv -> {
      WorkflowRunEntity run = inv.getArgument(0);
      if (run.getId() == null) {
        run.setId(UUID.randomUUID());
        run.setCreatedAt(Instant.now());
      }
      run.setUpdatedAt(Instant.now());
      runRows.removeIf(r -> r.getId().equals(run.getId()));
      runRows.add(run);
      return run;
    });
    when(runs.findById(any())).thenAnswer(inv ->
        runRows.stream().filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
    when(runs.findByStatus(any())).thenAnswer(inv ->
        runRows.stream().filter(r -> r.getStatus() == inv.getArgument(0)).toList());

    steps = mock(WorkflowRunStepRepository.class);
    when(steps.save(any())).thenAnswer(inv -> {
      WorkflowRunStepEntity step = inv.getArgument(0);
      if (step.getId() == null) step.setId(UUID.randomUUID());
      stepRows.removeIf(s -> s.getId().equals(step.getId()));
      stepRows.add(step);
      return step;
    });
    when(steps.findByRunIdAndNodeId(any(), any())).thenAnswer(inv -> stepRows.stream()
        .filter(s -> s.getRunId().equals(inv.getArgument(0))
            && s.getNodeId().equals(inv.getArgument(1)))
        .findFirst());
    when(steps.findByRunIdOrderByStartedAtAsc(any())).thenAnswer(inv -> stepRows.stream()
        .filter(s -> s.getRunId().equals(inv.<UUID>getArgument(0))).toList());

    sendEmailTool.setId(UUID.randomUUID());
    sendEmailTool.setConnectionId(UUID.randomUUID());
    sendEmailTool.setName("send_email");
    sendEmailTool.setDescription("Send an email");
    sendEmailTool.setInputSchema(Json.obj());
    sendEmailTool.setHttpMethod("POST");
    sendEmailTool.setPathTemplate("/messages");
    sendEmailTool.setSideEffecting(true);
    sendEmailTool.setEnabled(true);

    GeneratedToolRepository tools = mock(GeneratedToolRepository.class);
    when(tools.findById(sendEmailTool.getId())).thenReturn(Optional.of(sendEmailTool));

    ToolExecutor executor = new ToolExecutor() {
      private final java.util.Map<String, ToolResult> completed = new java.util.HashMap<>();

      @Override
      public ToolResult execute(GeneratedToolDto tool, JsonNode args, IdempotencyKey key) {
        // Mirrors the real executor's contract: a repeated key replays rather than re-sending.
        if (key != null && completed.containsKey(key.key())) return completed.get(key.key());
        toolInvocations++;
        ToolResult result = nextToolResult;
        if (key != null && result.ok()) completed.put(key.key(), result);
        return result;
      }
    };

    KillSwitch killSwitch = new KillSwitch() {
      @Override public boolean paused(UUID user) { return paused; }

      @Override public void setPaused(UUID user, boolean value) { paused = value; }

      @Override public void requireActive(UUID user) {
        if (paused) throw new AutonomyDeniedException("Autonomy is paused by the kill switch");
      }
    };

    AutonomyGate gate = new AutonomyGate() {
      @Override public Decision evaluate(UUID agent, String toolRef, JsonNode args) {
        return switch (toolRule) {
          case ALLOW -> Decision.allow("test");
          case ASK -> Decision.ask("needs approval", "test");
          case DENY -> Decision.deny("blocked by policy", "test");
        };
      }

      @Override public boolean agentEnabled(UUID agent) { return true; }
    };

    NotificationBus bus = new NotificationBus() {
      @Override
      public NotificationDto raise(NotificationKind kind, UUID sourceAgentId,
                                   NotificationContext ctx) {
        NotificationDto dto = new NotificationDto(UUID.randomUUID(), ctx.userId(), sourceAgentId,
            "Cato", kind, ctx.body(), ctx.detail(), ctx.taskId(), ctx.workflowId(), ctx.runId(),
            NotificationStatus.UNREAD, null, null, EscalationState.NONE, Instant.now(), null);
        raised.add(dto);
        return dto;
      }

      @Override public NotificationDto answer(UUID id, Answer a) { throw new UnsupportedOperationException(); }

      @Override public List<NotificationDto> list(UUID user, boolean unreadOnly) { return raised; }

      @Override public NotificationDto get(UUID id) { throw new UnsupportedOperationException(); }

      @Override public void markRead(UUID id) { }

      @Override public boolean awaitPermission(UUID id, java.time.Duration timeout) { return false; }
    };

    ObjectProvider<NotificationBus> busProvider = mock(ObjectProvider.class);
    when(busProvider.getIfAvailable()).thenReturn(bus);

    JexlSandbox sandbox = new JexlSandbox();
    List<NodeExecutor> executors = List.of(
        new TriggerNodeExecutor(),
        new EndNodeExecutor(),
        new TransformNodeExecutor(sandbox),
        new BranchNodeExecutor(sandbox),
        new InterventionNodeExecutor(),
        new ConnectorToolNodeExecutor(tools, executor, gate, sandbox),
        new CountingAgentStep());

    engine = new SagaWorkflowEngine(workflows, mock(WorkflowVersionRepository.class), runs, steps,
        new WorkflowValidator(), killSwitch, mock(AuditLog.class), busProvider, executors);
  }

  /** Counts how often an agent step ran, so re-execution after a resume is detectable. */
  public final java.util.Map<String, Integer> agentStepRuns = new java.util.HashMap<>();
  public java.util.function.Function<String, NodeExecutor.Outcome> agentStepBehaviour =
      nodeId -> NodeExecutor.Outcome.completed(Json.obj().put("text", "done " + nodeId));

  private final class CountingAgentStep implements NodeExecutor {
    @Override public NodeType type() { return NodeType.AGENT_STEP; }

    @Override
    public Outcome execute(Execution execution) {
      agentStepRuns.merge(execution.node().id(), 1, Integer::sum);
      return agentStepBehaviour.apply(execution.node().id());
    }
  }

  public WorkflowRunEntity run(UUID runId) {
    return runRows.stream().filter(r -> r.getId().equals(runId)).findFirst().orElseThrow();
  }

  public Optional<WorkflowRunStepEntity> step(UUID runId, String nodeId) {
    return stepRows.stream()
        .filter(s -> s.getRunId().equals(runId) && s.getNodeId().equals(nodeId)).findFirst();
  }
}
