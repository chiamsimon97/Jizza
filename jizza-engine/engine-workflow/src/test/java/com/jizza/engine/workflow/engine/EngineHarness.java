package com.jizza.engine.workflow.engine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.connector.IdempotencyKey;
import com.jizza.engine.api.connector.ToolExecutor;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.EscalationState;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.AutonomyDeniedException;
import com.jizza.engine.api.notification.Answer;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.persistence.entity.WorkflowRunStepEntity;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import com.jizza.engine.persistence.repository.WorkflowRunStepRepository;
import com.jizza.engine.persistence.repository.WorkflowVersionRepository;
import com.jizza.engine.workflow.nodes.BranchNodeExecutor;
import com.jizza.engine.workflow.nodes.ChildRuns;
import com.jizza.engine.workflow.nodes.ConnectorToolNodeExecutor;
import com.jizza.engine.workflow.nodes.DeviceActionNodeExecutor;
import com.jizza.engine.workflow.nodes.EndNodeExecutor;
import com.jizza.engine.workflow.nodes.ForEachNodeExecutor;
import com.jizza.engine.workflow.nodes.InterventionNodeExecutor;
import com.jizza.engine.workflow.nodes.JoinNodeExecutor;
import com.jizza.engine.workflow.nodes.ParallelNodeExecutor;
import com.jizza.engine.workflow.nodes.SubWorkflowNodeExecutor;
import com.jizza.engine.workflow.nodes.WaitNodeExecutor;
import com.jizza.engine.workflow.nodes.TransformNodeExecutor;
import com.jizza.engine.workflow.nodes.TriggerNodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.api.mail.MailSender;
import com.jizza.engine.connectors.http.GuardedHttpClient;
import com.jizza.engine.persistence.entity.UserEntity;
import com.jizza.engine.persistence.entity.WorkflowStorageEntity;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.persistence.repository.WorkflowStorageRepository;
import com.jizza.engine.workflow.nodes.ApprovalNodeExecutor;
import com.jizza.engine.workflow.nodes.FilterNodeExecutor;
import com.jizza.engine.workflow.nodes.HttpRequestNodeExecutor;
import com.jizza.engine.workflow.nodes.NotifyNodeExecutor;
import com.jizza.engine.workflow.nodes.SetVariableNodeExecutor;
import com.jizza.engine.workflow.nodes.StorageNodeExecutor;
import com.jizza.engine.workflow.nodes.SwitchNodeExecutor;
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
  /** Every request to close a run's questions: run id, step (or null for all), reason. */
  public final List<String[]> closed = new ArrayList<>();

  public final WorkflowEntity workflow = new WorkflowEntity();
  /** Every workflow the engine can find, so a test can add ones to run as steps. */
  public final List<WorkflowEntity> workflowRows = new ArrayList<>();
  /** What the engine and the wait step believe the time is. */
  public final MutableClock clock = new MutableClock();
  public final GeneratedToolEntity sendEmailTool = new GeneratedToolEntity();

  /** Counts real invocations of the side-effecting tool, which is what exactly-once is about. */
  public int toolInvocations;
  /** The arguments the last connector step was called with. */
  public JsonNode lastToolArgs;
  public ToolResult nextToolResult = ToolResult.ok(Json.obj().put("sent", true));
  public boolean paused;
  public AutonomyRule toolRule = AutonomyRule.ALLOW;
  /** Let Jizza decide was chosen, but Jizza runs the step, so the gate asks instead. */
  public boolean jizzaActs;
  public final List<com.jizza.engine.api.device.DeviceCommandRequest> deviceRequests =
      new ArrayList<>();
  /** The device was never reached: commands are not dispatched. */
  public boolean neverDelivered;
  public com.jizza.engine.api.device.DeviceCommandStatus nextDeviceStatus =
      com.jizza.engine.api.device.DeviceCommandStatus.SUCCEEDED;
  public JsonNode nextDeviceOutput = Json.obj();
  public final java.util.Map<String, JsonNode> deviceOutputs = new java.util.HashMap<>();
  /** Outputs given in turn, one per command of that action, before {@link #deviceOutputs}. */
  public final java.util.Map<String, java.util.ArrayDeque<JsonNode>> deviceOutputQueues =
      new java.util.HashMap<>();
  /** A failure for the next command of that action only: its output, with an error object. */
  public final java.util.Map<String, JsonNode> deviceFailures = new java.util.HashMap<>();
  /** Input schemas for browser actions a test needs as the catalog has them. */
  public final java.util.Map<String, JsonNode> deviceInputSchemas = new java.util.HashMap<>();
  /** Actions the fake device did not declare: sending one is refused, as the broker refuses it. */
  public final java.util.Set<String> unsupportedDeviceActions = new java.util.HashSet<>();
  public String nextDeviceErrorCode;
  public String nextDeviceError;
  public List<UUID> nextDeviceFiles = List.of();

  /** What the storage step keeps, by key, for this harness's user. */
  public final java.util.Map<String, WorkflowStorageEntity> stored = new java.util.LinkedHashMap<>();
  /** Calls the webhook step made: method, url, headers and body. */
  public final List<Object[]> httpCalls = java.util.Collections.synchronizedList(new ArrayList<>());
  public GuardedHttpClient.Response nextHttpResponse =
      new GuardedHttpClient.Response(200, "{\"ok\":true}", java.util.Map.of("content-type", "application/json"));
  /** Emails the notify step sent: to, subject, body. */
  public final List<String[]> emails = new ArrayList<>();
  /** Calls the MCP tool step made, as "tool args". */
  public final List<String> mcpCalls = java.util.Collections.synchronizedList(new ArrayList<>());
  public final UUID mcpServerId = UUID.randomUUID();
  /** One added MCP server with two tools; replace it to change what the step finds. */
  public com.jizza.engine.api.mcp.McpServerDto mcpServer = new com.jizza.engine.api.mcp.McpServerDto(
      mcpServerId, userId, UUID.randomUUID(), "Kite", "kite", "https://mcp.kite.example/mcp",
      com.jizza.engine.api.mcp.McpAuthType.NONE, null, false, false, true, List.of("jizza"), false,
      "connected", null, null, null, List.of(
          new com.jizza.engine.api.mcp.McpToolDto("get_quote", "mcp_kite__get_quote", null, null,
              Json.obj(), true, com.jizza.engine.api.enums.ActionType.READ, false, null, null, null),
          new com.jizza.engine.api.mcp.McpToolDto("place_order", "mcp_kite__place_order", null,
              null, Json.obj(), false, null, false, null, null, null)), null, null);

  /** Failed web steps handed over for a suggested fix. */
  public final List<com.jizza.engine.api.webauto.LocatorFixes.FailedStep> proposedFixes =
      new ArrayList<>();
  /** The device step, to switch fast replay on. */
  public DeviceActionNodeExecutor deviceActions;

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
    workflowRows.add(workflow);
    when(workflows.findById(any())).thenAnswer(inv -> workflowRows.stream()
        .filter(w -> w.getId().equals(inv.getArgument(0))).findFirst());

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
      public synchronized ToolResult execute(GeneratedToolDto tool, JsonNode args,
                                             IdempotencyKey key) {
        // Mirrors the real executor's contract: a repeated key replays rather than re-sending.
        if (key != null && completed.containsKey(key.key())) return completed.get(key.key());
        toolInvocations++;
        lastToolArgs = args;
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
        if (jizzaActs) {
          return new Decision(AutonomyRule.JIZZA, "Jizza decides", "test")
              .askedBecauseJizzaActs("Jizza doesn't approve its own actions");
        }
        return switch (toolRule) {
          case ALLOW -> Decision.allow("test");
          case ASK -> Decision.ask("needs approval", "test");
          case JIZZA -> new Decision(AutonomyRule.JIZZA, "Jizza decides", "test");
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

      @Override public int closeForRun(UUID runId, String nodeId, String reason) {
        closed.add(new String[] {String.valueOf(runId), nodeId, reason});
        return 0;
      }
    };

    ObjectProvider<NotificationBus> busProvider = mock(ObjectProvider.class);
    when(busProvider.getIfAvailable()).thenReturn(bus);

    JexlSandbox sandbox = new JexlSandbox();
    SagaWorkflowEngine[] self = new SagaWorkflowEngine[1];
    ObjectProvider<SagaWorkflowEngine> engineProvider = mock(ObjectProvider.class);
    when(engineProvider.getIfAvailable()).thenAnswer(inv -> self[0]);
    ChildRuns childRuns = new ChildRuns(engineProvider, runs);
    var deviceCommands = deviceCommands();
    var deviceCatalog = deviceCatalog();
    WorkflowValidator validator = new WorkflowValidator();
    validator.setDeviceActions(deviceCatalog);
    List<NodeExecutor> executors = List.of(
        new TriggerNodeExecutor(),
        new EndNodeExecutor(sandbox),
        new ParallelNodeExecutor(),
        new JoinNodeExecutor(),
        new WaitNodeExecutor(sandbox, clock),
        new SubWorkflowNodeExecutor(childRuns, sandbox),
        new ForEachNodeExecutor(childRuns, sandbox),
        new TransformNodeExecutor(sandbox),
        new com.jizza.engine.workflow.nodes.ScriptNodeExecutor(sandbox, null),
        new BranchNodeExecutor(sandbox),
        new InterventionNodeExecutor(),
        new ConnectorToolNodeExecutor(tools, executor, gate, sandbox),
        new ApprovalNodeExecutor(sandbox, clock),
        new FilterNodeExecutor(sandbox),
        new SwitchNodeExecutor(sandbox),
        new SetVariableNodeExecutor(sandbox),
        new StorageNodeExecutor(sandbox, storageRepository(), clock),
        new HttpRequestNodeExecutor(sandbox, httpClient()),
        deviceActions = new DeviceActionNodeExecutor(deviceCommands, deviceCatalog, gate, sandbox,
            mock(com.jizza.engine.persistence.repository.AgentRepository.class), locatorFixes(), storageProvider()),
        new NotifyNodeExecutor(sandbox, busProvider, mailProvider(), userRepository()),
        new com.jizza.engine.workflow.nodes.McpToolNodeExecutor(mcpServers(), gate, sandbox,
            mock(com.jizza.engine.persistence.repository.AgentRepository.class),
            mock(ObjectProvider.class)),
        new CountingAgentStep());
    deviceActions.setClock(clock);

    engine = new SagaWorkflowEngine(workflows, mock(WorkflowVersionRepository.class), runs, steps,
        validator, killSwitch, mock(AuditLog.class), busProvider, executors, clock,
        sandbox);
    engine.setDeviceCommands(deviceCommands);
    self[0] = engine;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<com.jizza.engine.api.webauto.LocatorFixes> locatorFixes() {
    var fixes = mock(com.jizza.engine.api.webauto.LocatorFixes.class);
    org.mockito.Mockito.doAnswer(inv -> {
      proposedFixes.add(inv.getArgument(0));
      return null;
    }).when(fixes).propose(any());
    ObjectProvider<com.jizza.engine.api.webauto.LocatorFixes> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(fixes);
    return provider;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<com.jizza.engine.api.device.DeviceActionCatalog> deviceCatalog() {
    var catalog = mock(com.jizza.engine.api.device.DeviceActionCatalog.class);
    when(catalog.resolve(any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
      String action = inv.getArgument(0);
      if (!action.startsWith("browser.")) return Optional.empty();
      int version = inv.<Integer>getArgument(1);
      com.fasterxml.jackson.databind.node.ObjectNode input = Json.obj();
      if ("browser.open".equals(action)) {
        input.put("type", "object").putArray("required").add("url");
      }
      if (deviceInputSchemas.containsKey(action)) {
        input = (com.fasterxml.jackson.databind.node.ObjectNode) deviceInputSchemas.get(action);
      }
      return Optional.of(new com.jizza.engine.api.device.DeviceActionSpec(action,
          version == 0 ? 1 : version, "browser", com.jizza.engine.api.enums.ActionType.READ,
          action, action, input, Json.obj(), List.of(), List.of(), 30));
    });
    ObjectProvider<com.jizza.engine.api.device.DeviceActionCatalog> provider =
        mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(catalog);
    return provider;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<com.jizza.engine.api.device.DeviceCommands> deviceCommands() {
    var broker = mock(com.jizza.engine.api.device.DeviceCommands.class);
    java.util.Map<UUID, com.jizza.engine.api.device.DeviceCommandDto> commands =
        new java.util.HashMap<>();
    java.util.Map<String, com.jizza.engine.api.device.DeviceCommandDto> idempotent =
        new java.util.HashMap<>();
    org.mockito.stubbing.Answer<com.jizza.engine.api.device.DeviceCommandDto> submit = inv -> {
      var request = inv.<com.jizza.engine.api.device.DeviceCommandRequest>getArgument(0);
      deviceRequests.add(request);
      if (unsupportedDeviceActions.contains(request.action())) {
        throw new com.jizza.engine.api.error.ValidationException("Browser cannot " + request.action());
      }
      if (idempotent.containsKey(request.idempotencyKey())) {
        return idempotent.get(request.idempotencyKey());
      }
      UUID id = UUID.randomUUID();
      UUID deviceId = request.target().deviceId() == null
          ? UUID.nameUUIDFromBytes("browser-device".getBytes(java.nio.charset.StandardCharsets.UTF_8))
          : request.target().deviceId();
      var queue = deviceOutputQueues.get(request.action());
      JsonNode failure = deviceFailures.remove(request.action());
      JsonNode output = failure != null ? failure.deepCopy()
          : queue != null && !queue.isEmpty() ? queue.poll().deepCopy()
          : deviceOutputs.getOrDefault(request.action(), nextDeviceOutput).deepCopy();
      var status = failure != null ? com.jizza.engine.api.device.DeviceCommandStatus.FAILED
          : nextDeviceStatus;
      var command = new com.jizza.engine.api.device.DeviceCommandDto(id, deviceId, "Browser",
          request.userId(), request.runId(), request.nodeId(), request.action(), request.version(),
          request.input(), status, output, nextDeviceErrorCode, nextDeviceError, null,
          nextDeviceFiles, Instant.now().plusSeconds(30), Instant.now(),
          neverDelivered ? null : Instant.now(), Instant.now(), Instant.now());
      commands.put(id, command);
      idempotent.put(request.idempotencyKey(), command);
      return command;
    };
    when(broker.submit(any())).thenAnswer(submit);
    when(broker.submitSafetyCleanup(any())).thenAnswer(submit);
    when(broker.find(any())).thenAnswer(inv ->
        Optional.ofNullable(commands.get(inv.<UUID>getArgument(0))));
    ObjectProvider<com.jizza.engine.api.device.DeviceCommands> provider =
        mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(broker);
    return provider;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<com.jizza.engine.api.mcp.McpServers> mcpServers() {
    var store = mock(com.jizza.engine.api.mcp.McpServers.class);
    when(store.find(any())).thenAnswer(inv -> Optional.ofNullable(mcpServer)
        .filter(s -> s.id().equals(inv.getArgument(0))));
    when(store.call(any(), any(), any())).thenAnswer(inv -> {
      mcpCalls.add(inv.getArgument(1) + " " + inv.getArgument(2));
      return nextToolResult;
    });
    ObjectProvider<com.jizza.engine.api.mcp.McpServers> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(store);
    return provider;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<WorkflowStorageRepository> storageProvider() {
    ObjectProvider<WorkflowStorageRepository> provider = mock(ObjectProvider.class);
    WorkflowStorageRepository repo = storageRepository();
    when(provider.getIfAvailable()).thenReturn(repo);
    return provider;
  }

  private WorkflowStorageRepository storageRepository() {
    WorkflowStorageRepository repo = mock(WorkflowStorageRepository.class);
    when(repo.findByUserIdAndKey(any(), any())).thenAnswer(inv ->
        Optional.ofNullable(stored.get(inv.<String>getArgument(1))));
    when(repo.lockByUserIdAndKey(any(), any())).thenAnswer(inv ->
        Optional.ofNullable(stored.get(inv.<String>getArgument(1))));
    when(repo.countByUserId(any())).thenAnswer(inv -> (long) stored.size());
    when(repo.save(any())).thenAnswer(inv -> {
      WorkflowStorageEntity row = inv.getArgument(0);
      stored.put(row.getKey(), row);
      return row;
    });
    org.mockito.Mockito.doAnswer(inv -> {
      stored.remove(inv.<WorkflowStorageEntity>getArgument(0).getKey());
      return null;
    }).when(repo).delete(any());
    return repo;
  }

  private GuardedHttpClient httpClient() {
    GuardedHttpClient http = mock(GuardedHttpClient.class);
    when(http.send(any(), any(), any(), any(), any())).thenAnswer(inv -> {
      httpCalls.add(new Object[] {inv.getArgument(0), inv.getArgument(1).toString(),
          inv.getArgument(2), inv.getArgument(3)});
      return nextHttpResponse;
    });
    return http;
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<MailSender> mailProvider() {
    ObjectProvider<MailSender> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn((to, subject, body) ->
        emails.add(new String[] {to, subject, body}));
    return provider;
  }

  private UserRepository userRepository() {
    UserRepository users = mock(UserRepository.class);
    UserEntity me = new UserEntity();
    me.setEmail("owner@jizza.local");
    when(users.findById(userId)).thenReturn(Optional.of(me));
    return users;
  }

  /** Adds another approved workflow of the same person, for a step to run. */
  public WorkflowEntity addWorkflow(String name, WorkflowGraph graph) {
    WorkflowEntity other = new WorkflowEntity();
    other.setId(UUID.randomUUID());
    other.setUserId(userId);
    other.setName(name);
    other.setGraph(graph.toJson());
    other.setGraphFingerprint(graph.fingerprint());
    other.setOrigin(WorkflowOrigin.USER_BUILT);
    other.setStatus(WorkflowStatus.APPROVED);
    other.setOwningAgentId(agentId);
    other.setVersion(1);
    workflowRows.add(other);
    return other;
  }

  /** A clock a test can move forward. */
  public static final class MutableClock extends java.time.Clock {
    private volatile Instant now = Instant.parse("2026-10-21T08:00:00Z");

    public void advance(java.time.Duration by) { now = now.plus(by); }

    @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }

    @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }

    @Override public Instant instant() { return now; }
  }

  /** Counts how often an agent step ran, so re-execution after a resume is detectable. */
  public final java.util.Map<String, Integer> agentStepRuns =
      new java.util.concurrent.ConcurrentHashMap<>();
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
