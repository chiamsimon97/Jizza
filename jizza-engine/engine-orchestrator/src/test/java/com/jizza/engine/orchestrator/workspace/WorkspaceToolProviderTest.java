package com.jizza.engine.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.entity.NotificationEntity;
import com.jizza.engine.persistence.entity.ScriptFunctionEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.DeviceRepository;
import com.jizza.engine.persistence.repository.McpServerRepository;
import com.jizza.engine.persistence.repository.NotificationRepository;
import com.jizza.engine.persistence.repository.ScheduledTaskRepository;
import com.jizza.engine.persistence.repository.ScriptFunctionInstallRepository;
import com.jizza.engine.persistence.repository.ScriptFunctionRepository;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkspaceToolProviderTest {

  private final UUID userId = UUID.randomUUID();
  private final WorkflowRepository workflows = mock(WorkflowRepository.class);
  private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
  private final ScriptFunctionRepository functions = mock(ScriptFunctionRepository.class);
  private final DeviceRepository devices = mock(DeviceRepository.class);
  private final NotificationRepository notifications = mock(NotificationRepository.class);
  private final ScheduledTaskRepository tasks = mock(ScheduledTaskRepository.class);
  private Workspace workspace;
  private WorkspaceToolProvider provider;
  private AgentEntity jizza;
  private AgentEntity kai;
  private WorkflowEntity booking;

  @BeforeEach
  void setUp() {
    AgentRepository agents = mock(AgentRepository.class);
    jizza = agent("Jizza", true);
    kai = agent("Kai", false);
    when(agents.findById(jizza.getId())).thenReturn(Optional.of(jizza));
    when(agents.findById(kai.getId())).thenReturn(Optional.of(kai));
    when(agents.findByUserIdAndIsOrchestratorFalseAndEnabledTrue(userId)).thenReturn(List.of(kai));

    booking = new WorkflowEntity();
    booking.setId(UUID.randomUUID());
    booking.setUserId(userId);
    booking.setName("Ramayanam Ticket Booking");
    booking.setStatus(WorkflowStatus.DRAFT);
    booking.setOrigin(WorkflowOrigin.USER_BUILT);
    booking.setGraph(Json.read("""
        {"nodes":[{"id":"n1","type":"DEVICE_ACTION","label":"Open BookMyShow"}],"edges":[]}"""));
    when(workflows.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(booking));
    when(workflows.findById(booking.getId())).thenReturn(Optional.of(booking));

    ScriptFunctionEntity gst = new ScriptFunctionEntity();
    gst.setId(UUID.randomUUID());
    gst.setUserId(userId);
    gst.setName("gstAmount");
    gst.setDescription("Adds GST to an amount");
    gst.setScript("amount * 1.18");
    when(functions.findByUserIdOrderByNameAsc(userId)).thenReturn(List.of(gst));

    DeviceEntity chrome = new DeviceEntity();
    chrome.setId(UUID.randomUUID());
    chrome.setOwnerUserId(userId);
    chrome.setName("Chromium browser");
    chrome.setPlatform("browser");
    chrome.setStatus("active");
    when(devices.findByOwnerUserIdAndStatusOrderByCreatedAtAsc(userId, "active")).thenReturn(List.of(chrome));

    NotificationEntity ask = new NotificationEntity();
    ask.setId(UUID.randomUUID());
    ask.setUserId(userId);
    ask.setKind(NotificationKind.INSTRUCTION_REQUIRED);
    ask.setStatus(NotificationStatus.UNREAD);
    ask.setBody("Which seats should I pick?");
    ask.setCreatedAt(Instant.parse("2026-11-06T09:00:00Z"));
    when(notifications.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(ask));

    when(runs.findRows(eq(userId), any(), any(), any())).thenReturn(List.of());

    ConnectionRepository connections = mock(ConnectionRepository.class);
    McpServerRepository mcp = mock(McpServerRepository.class);
    workspace = new Workspace(workflows, runs, functions, mock(ScriptFunctionInstallRepository.class),
        agents, devices, connections, mcp, tasks, notifications,
        Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC));
    provider = new WorkspaceToolProvider(agents, workspace);
  }

  @Test
  void onlyJizzaGetsTheWorkspaceTools() {
    assertThat(provider.toolsFor(jizza.getId())).extracting(t -> t.name()).containsExactly(
        WorkspaceToolProvider.OVERVIEW, WorkspaceToolProvider.SEARCH, WorkspaceToolProvider.LIST,
        WorkspaceToolProvider.GET);
    assertThat(provider.toolsFor(kai.getId())).isEmpty();
  }

  @Test
  void aLooseDescriptionFindsTheWorkflowAcrossModules() {
    ToolResult result = call(WorkspaceToolProvider.SEARCH, "{\"query\":\"the ramayana movie booking\"}");

    assertThat(result.output().path("found").get(0).path("name").asText())
        .isEqualTo("Ramayanam Ticket Booking");
    assertThat(result.output().path("found").get(0).path("module").asText()).isEqualTo("workflows");
  }

  @Test
  void theOverviewCoversEveryModuleAndSaysHowToActOnIt() {
    ToolResult result = call(WorkspaceToolProvider.OVERVIEW, "{}");

    assertThat(result.output().path("modules")).hasSize(Workspace.Module.values().length);
    assertThat(result.output().path("modules").findValuesAsText("how_to_act"))
        .anyMatch(s -> s.contains("run_saved_workflow"));
  }

  @Test
  void getGivesAWorkflowsStepsAndRefusesSomeoneElses() {
    ToolResult mine = call(WorkspaceToolProvider.GET,
        "{\"module\":\"workflows\",\"id\":\"" + booking.getId() + "\"}");
    assertThat(mine.output().path("steps").get(0).path("label").asText()).isEqualTo("Open BookMyShow");

    booking.setUserId(UUID.randomUUID());
    assertThat(call(WorkspaceToolProvider.GET,
        "{\"module\":\"workflows\",\"id\":\"" + booking.getId() + "\"}").ok()).isFalse();
  }

  @Test
  void theDigestNamesWhatTheUserHasAndWhatIsWaiting() {
    String digest = workspace.digest(userId);

    assertThat(digest).contains("Workflows (1): \"Ramayanam Ticket Booking\" [draft]")
        .contains("Functions (1): \"gstAmount\" [yours]")
        .contains("Devices (active) (1): \"Chromium browser\"")
        .contains("Inbox: 1 waiting on the user");
  }

  @Test
  void anUnknownModuleIsExplained() {
    ToolResult result = call(WorkspaceToolProvider.LIST, "{\"module\":\"spaceships\"}");

    assertThat(result.ok()).isFalse();
    assertThat(result.error()).contains("workflows");
  }

  private ToolResult call(String tool, String args) {
    return provider.invoke(jizza.getId(), new ToolCall("c1", tool, Json.read(args)));
  }

  private AgentEntity agent(String name, boolean orchestrator) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setUserId(userId);
    a.setKey(name.toLowerCase());
    a.setName(name);
    a.setOrchestrator(orchestrator);
    a.setEnabled(true);
    return a;
  }
}
