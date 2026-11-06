package com.jizza.engine.orchestrator.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.enums.TaskStatus;
import com.jizza.engine.api.util.FuzzyText;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.DeviceEntity;
import com.jizza.engine.persistence.entity.McpServerEntity;
import com.jizza.engine.persistence.entity.NotificationEntity;
import com.jizza.engine.persistence.entity.ScheduledTaskEntity;
import com.jizza.engine.persistence.entity.ScriptFunctionEntity;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
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
import com.jizza.engine.persistence.repository.WorkflowRunRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the user has built or set up in Jizza, as one place Jizza can look things up in:
 * workflows and their runs, functions, agents, devices, connectors, scheduled tasks and the
 * questions waiting in the inbox. Read only; acting on any of it goes through the module's own
 * tools (run_saved_workflow, delegate_to_specialist, device_*, schedule_task, ...), each with its
 * own guardrails.
 *
 * <p>Each module is described the same way (an id, a name, a status and a line about it) so a
 * search can run across all of them with the same forgiving word matching ({@link FuzzyText}).
 */
@Component
public class Workspace {

  /** The modules, by the name Jizza uses for them. */
  public enum Module {
    WORKFLOWS("workflows", "Saved workflows: fixed steps that do a job the same way every time",
        "Run one with run_saved_workflow (find_saved_workflows gives inputs and rules). Open the "
            + "Workflows page to build or edit one."),
    RUNS("runs", "Workflow runs: what ran, when, and how it went",
        "workspace_get a run for its error and the step it stopped at. Open the Run history page."),
    FUNCTIONS("functions", "Functions: small Jizza-script functions the Script step calls as fn.name()",
        "workspace_get one for its inputs and script. Open the Functions page to edit or test."),
    AGENTS("agents", "Specialist agents on the user's team",
        "Ask one with delegate_to_specialist. Open the Agents page to change one."),
    DEVICES("devices", "Paired devices (browsers, computers, phones) Jizza can act on",
        "Act on an active one with its device_* tools. Open the Devices page to pair or manage."),
    CONNECTORS("connectors", "Connected services and MCP servers",
        "Their actions are tools you already have when connected. One that needs signing in "
            + "again cannot be used until the user reconnects it on the Connectors page."),
    SCHEDULES("schedules", "Scheduled tasks: jobs that run at a time or on a repeat",
        "Add one with schedule_task. Open the Tasks page to change or cancel one."),
    NOTIFICATIONS("notifications", "Inbox: questions and approvals waiting on the user, and updates",
        "Tell the user what is waiting and open the Notifications page; you cannot answer for them.");

    public final String key;
    public final String about;
    public final String howToAct;

    Module(String key, String about, String howToAct) {
      this.key = key;
      this.about = about;
      this.howToAct = howToAct;
    }

    public static Optional<Module> of(String key) {
      if (key == null) return Optional.empty();
      String k = key.strip().toLowerCase(Locale.ROOT);
      for (Module m : values()) {
        if (m.key.equals(k) || (m.key.endsWith("s") && m.key.equals(k + "s"))) return Optional.of(m);
      }
      if (k.equals("tasks") || k.equals("task")) return Optional.of(SCHEDULES);
      if (k.equals("inbox") || k.equals("questions")) return Optional.of(NOTIFICATIONS);
      if (k.equals("mcp") || k.equals("connections") || k.equals("services")) return Optional.of(CONNECTORS);
      return Optional.empty();
    }
  }

  /**
   * One thing in the workspace.
   *
   * @param text everything a search may match besides the name
   */
  public record Item(Module module, String id, String name, String status, String detail, String text) {
    ObjectNode toJson() {
      ObjectNode out = Json.obj().put("module", module.key).put("id", id).put("name", name);
      if (status != null) out.put("status", status);
      if (detail != null && !detail.isBlank()) out.put("about", cut(detail, 240));
      return out;
    }
  }

  static final Set<RunStatus> UNFINISHED =
      EnumSet.of(RunStatus.RUNNING, RunStatus.PAUSED_WAITING_INSTRUCTION, RunStatus.WAITING);
  static final Set<TaskStatus> SCHEDULED =
      EnumSet.of(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.WAITING_APPROVAL);
  /** How far back the runs module looks. */
  static final Duration RUN_WINDOW = Duration.ofDays(30);

  private final WorkflowRepository workflows;
  private final WorkflowRunRepository runs;
  private final ScriptFunctionRepository functions;
  private final ScriptFunctionInstallRepository installs;
  private final AgentRepository agents;
  private final DeviceRepository devices;
  private final ConnectionRepository connections;
  private final McpServerRepository mcpServers;
  private final ScheduledTaskRepository tasks;
  private final NotificationRepository notifications;
  private final Clock clock;

  @Autowired
  public Workspace(WorkflowRepository workflows, WorkflowRunRepository runs,
                   ScriptFunctionRepository functions, ScriptFunctionInstallRepository installs,
                   AgentRepository agents, DeviceRepository devices,
                   ConnectionRepository connections, McpServerRepository mcpServers,
                   ScheduledTaskRepository tasks, NotificationRepository notifications) {
    this(workflows, runs, functions, installs, agents, devices, connections, mcpServers, tasks,
        notifications, Clock.systemUTC());
  }

  Workspace(WorkflowRepository workflows, WorkflowRunRepository runs,
            ScriptFunctionRepository functions, ScriptFunctionInstallRepository installs,
            AgentRepository agents, DeviceRepository devices, ConnectionRepository connections,
            McpServerRepository mcpServers, ScheduledTaskRepository tasks,
            NotificationRepository notifications, Clock clock) {
    this.workflows = workflows;
    this.runs = runs;
    this.functions = functions;
    this.installs = installs;
    this.agents = agents;
    this.devices = devices;
    this.connections = connections;
    this.mcpServers = mcpServers;
    this.tasks = tasks;
    this.notifications = notifications;
    this.clock = clock;
  }

  /** The items of one module, in the order the app shows them. */
  @Transactional(readOnly = true)
  public List<Item> list(UUID userId, Module module) {
    return switch (module) {
      case WORKFLOWS -> workflows.findByUserIdOrderByCreatedAtDesc(userId).stream()
          .map(Workspace::workflow).toList();
      case RUNS -> runs(userId, 100);
      case FUNCTIONS -> functionsOf(userId);
      case AGENTS -> agents.findByUserIdAndIsOrchestratorFalseAndEnabledTrue(userId).stream()
          .map(Workspace::agent).toList();
      case DEVICES -> devices.findByOwnerUserIdAndStatusOrderByCreatedAtAsc(userId, "active").stream()
          .map(Workspace::device).toList();
      case CONNECTORS -> {
        List<Item> out = new ArrayList<>();
        connections.findByUserIdOrderByServiceNameAsc(userId).forEach(c -> out.add(connection(c)));
        mcpServers.findByUserIdOrderByNameAsc(userId).forEach(m -> out.add(mcp(m)));
        yield out;
      }
      case SCHEDULES -> tasks.findByUserIdOrderByNextRunAtAsc(userId).stream()
          .filter(t -> SCHEDULED.contains(t.getStatus())).map(this::task).toList();
      case NOTIFICATIONS -> notifications.findByUserIdOrderByCreatedAtDesc(userId).stream()
          .filter(Workspace::open).limit(50).map(Workspace::notification).toList();
    };
  }

  /** What in the given modules (all when empty) shares words with {@code query}, best first. */
  @Transactional(readOnly = true)
  public List<Item> search(UUID userId, String query, List<Module> modules, int limit) {
    List<Module> where = modules == null || modules.isEmpty() ? List.of(Module.values()) : modules;
    record Hit(Item item, double score) {}
    List<Hit> hits = new ArrayList<>();
    for (Module module : where) {
      for (Item item : list(userId, module)) {
        double named = FuzzyText.coverage(query, item.name());
        double all = FuzzyText.coverage(query, item.name() + " " + nz(item.detail()) + " " + nz(item.text()));
        double score = 0.4 * named + 0.6 * all;
        if (score > 0) hits.add(new Hit(item, score));
      }
    }
    hits.sort(Comparator.comparingDouble(Hit::score).reversed());
    return hits.stream().limit(Math.max(1, limit)).map(Hit::item).toList();
  }

  /** One item in full, or empty when it is not the user's. */
  @Transactional(readOnly = true)
  public Optional<ObjectNode> get(UUID userId, Module module, UUID id) {
    return switch (module) {
      case WORKFLOWS -> workflows.findById(id).filter(w -> userId.equals(w.getUserId()))
          .map(w -> workflowDetail(userId, w));
      case RUNS -> runs.findById(id).filter(r -> userId.equals(r.getUserId())).map(this::runDetail);
      case FUNCTIONS -> functions.findById(id)
          .filter(f -> f.getUserId() == null || userId.equals(f.getUserId()))
          .map(Workspace::functionDetail);
      case AGENTS -> agents.findById(id).filter(a -> userId.equals(a.getUserId()))
          .map(Workspace::agentDetail);
      case DEVICES -> devices.findById(id).filter(d -> userId.equals(d.getOwnerUserId()))
          .map(Workspace::deviceDetail);
      case CONNECTORS -> connections.findById(id).filter(c -> userId.equals(c.getUserId()))
          .map(c -> connection(c).toJson())
          .or(() -> mcpServers.findById(id).filter(m -> userId.equals(m.getUserId()))
              .map(Workspace::mcpDetail));
      case SCHEDULES -> tasks.findById(id).filter(t -> userId.equals(t.getUserId()))
          .map(this::taskDetail);
      case NOTIFICATIONS -> notifications.findById(id).filter(n -> userId.equals(n.getUserId()))
          .map(Workspace::notificationDetail);
    };
  }

  /**
   * A few lines on what the user has, for Jizza's context each turn: how many of each, the names
   * that matter most, and anything waiting. Kept short; the tools give the rest.
   */
  @Transactional(readOnly = true)
  public String digest(UUID userId) {
    StringBuilder out = new StringBuilder();
    List<Item> flows = list(userId, Module.WORKFLOWS);
    line(out, "Workflows", flows, 25);
    List<Item> recent = runs(userId, 50);
    long running = recent.stream().filter(r -> "running".equals(r.status())
        || "waiting".equals(r.status()) || "paused_waiting_instruction".equals(r.status())).count();
    Instant dayAgo = clock.instant().minus(Duration.ofDays(1));
    long failed = runs.findRows(userId, dayAgo, clock.instant().plusSeconds(60), PageRequest.of(0, 100))
        .stream().filter(r -> r.status() == RunStatus.FAILED).count();
    if (running > 0 || failed > 0) {
      out.append("- Runs: ").append(running).append(" not finished, ").append(failed)
          .append(" failed in the last day\n");
    }
    line(out, "Functions", functionsOf(userId), 15);
    line(out, "Agents", list(userId, Module.AGENTS), 15);
    line(out, "Devices (active)", list(userId, Module.DEVICES), 10);
    line(out, "Connectors", list(userId, Module.CONNECTORS), 15);
    line(out, "Scheduled tasks", list(userId, Module.SCHEDULES), 10);
    long waiting = notifications.findByUserIdOrderByCreatedAtDesc(userId).stream()
        .filter(Workspace::open).filter(n -> n.getKind().blocking()).count();
    if (waiting > 0) out.append("- Inbox: ").append(waiting).append(" waiting on the user\n");
    return out.toString();
  }

  private static void line(StringBuilder out, String label, List<Item> items, int max) {
    if (items.isEmpty()) {
      out.append("- ").append(label).append(": none\n");
      return;
    }
    out.append("- ").append(label).append(" (").append(items.size()).append("): ");
    List<String> names = new ArrayList<>();
    for (Item item : items.stream().limit(max).toList()) {
      String status = item.status();
      boolean plain = status == null || Set.of("approved", "active", "connected", "enabled",
          "pending", "online").contains(status);
      names.add("\"" + item.name() + "\"" + (plain ? "" : " [" + status + "]"));
    }
    out.append(String.join(", ", names));
    if (items.size() > max) out.append(", and ").append(items.size() - max).append(" more");
    out.append('\n');
  }

  // ---- items -------------------------------------------------------------------------------

  private static Item workflow(WorkflowEntity w) {
    String status = w.isDisabled() ? "turned off" : lower(w.getStatus());
    boolean saved = w.getReuse() != null && !w.getReuse().isNull();
    StringBuilder text = new StringBuilder();
    for (JsonNode node : w.getGraph() == null ? Json.mapper().createArrayNode() : w.getGraph().path("nodes")) {
      text.append(' ').append(node.path("label").asText(""));
      if (node.path("config").path("url").isTextual()) text.append(' ').append(node.path("config").path("url").asText());
    }
    if (saved) text.append(' ').append(w.getReuse().path("summary").asText(""))
        .append(' ').append(w.getReuse().path("examples").toString());
    return new Item(Module.WORKFLOWS, w.getId().toString(), w.getName(), status,
        w.getDescription(), text.toString());
  }

  private List<Item> runs(UUID userId, int limit) {
    Instant now = clock.instant();
    List<WorkflowRunRow> rows = runs.findRows(userId, now.minus(RUN_WINDOW), now.plusSeconds(60),
        PageRequest.of(0, limit));
    Map<UUID, String> names = workflowNames(userId);
    return rows.stream().map(r -> new Item(Module.RUNS, r.id().toString(),
        names.getOrDefault(r.workflowId(), "(deleted workflow)") + " at " + r.createdAt(),
        lower(r.status()),
        (r.error() == null ? "" : "Error: " + r.error() + ". ")
            + (r.currentNode() == null ? "" : "Step: " + r.currentNode() + ". ")
            + "Started by " + nz(r.triggerSource()),
        null)).toList();
  }

  private Map<UUID, String> workflowNames(UUID userId) {
    Map<UUID, String> names = new HashMap<>();
    workflows.findByUserIdOrderByCreatedAtDesc(userId).forEach(w -> names.put(w.getId(), w.getName()));
    return names;
  }

  private List<Item> functionsOf(UUID userId) {
    List<Item> out = new ArrayList<>();
    functions.findByUserIdOrderByNameAsc(userId).forEach(f -> out.add(function(f, "yours")));
    installs.findByUserId(userId).forEach(i -> functions.findById(i.getFunctionId())
        .ifPresent(f -> out.add(function(f, "installed public"))));
    return out;
  }

  private static Item function(ScriptFunctionEntity f, String status) {
    return new Item(Module.FUNCTIONS, f.getId().toString(), f.getName(), status,
        f.getDescription(), "fn." + f.getName());
  }

  private static Item agent(AgentEntity a) {
    return new Item(Module.AGENTS, a.getId().toString(), a.getName(),
        a.getStatus() == null ? null : a.getStatus().toLowerCase(Locale.ROOT),
        (a.getDomain() == null ? "" : a.getDomain() + ". ") + nz(a.getDescription()),
        "key " + a.getKey());
  }

  private static Item device(DeviceEntity d) {
    return new Item(Module.DEVICES, d.getId().toString(), d.getName(),
        d.getStatus(), d.getPlatform() + (d.getLastSeenAt() == null ? ""
            : ", last seen " + d.getLastSeenAt()), d.getHostname());
  }

  private static Item connection(ConnectionEntity c) {
    return new Item(Module.CONNECTORS, c.getId().toString(), c.getServiceName(),
        c.getStatus() == null ? null : switch (c.getStatus()) {
          case CONNECTED -> "connected";
          case NEEDS_REAUTH -> "needs signing in again";
          case ERROR -> "error";
          case NEW -> "not connected yet";
        },
        nz(c.getDescription()), nz(c.getCategory()) + " " + nz(c.getSlug()));
  }

  private static Item mcp(McpServerEntity m) {
    StringBuilder tools = new StringBuilder();
    if (m.getTools() != null) m.getTools().forEach(t -> tools.append(' ').append(t.path("name").asText("")));
    return new Item(Module.CONNECTORS, m.getId().toString(), m.getName() + " (MCP server)",
        m.isEnabled() ? nz(m.getStatus()).toLowerCase(Locale.ROOT) : "turned off",
        m.getLastError() == null ? null : "Last error: " + m.getLastError(), tools.toString());
  }

  private Item task(ScheduledTaskEntity t) {
    return new Item(Module.SCHEDULES, t.getId().toString(), t.getTitle(), lower(t.getStatus()),
        schedule(t) + (t.getNextRunAt() == null ? "" : ", next " + t.getNextRunAt()), null);
  }

  private static Item notification(NotificationEntity n) {
    return new Item(Module.NOTIFICATIONS, n.getId().toString(), cut(n.getBody(), 80),
        lower(n.getKind()), n.getBody(), null);
  }

  private static boolean open(NotificationEntity n) {
    return n.getArchivedAt() == null
        && (n.getStatus() == NotificationStatus.UNREAD || n.getStatus() == NotificationStatus.READ);
  }

  // ---- details -----------------------------------------------------------------------------

  private ObjectNode workflowDetail(UUID userId, WorkflowEntity w) {
    ObjectNode out = workflow(w).toJson();
    out.put("version", w.getVersion());
    out.put("saved_for_reuse", w.getReuse() != null && !w.getReuse().isNull());
    ArrayNode steps = out.putArray("steps");
    for (JsonNode node : w.getGraph() == null ? Json.mapper().createArrayNode() : w.getGraph().path("nodes")) {
      ObjectNode step = steps.addObject().put("id", node.path("id").asText())
          .put("type", node.path("type").asText()).put("label", node.path("label").asText(""));
      if (steps.size() >= 60) break;
      if (step.path("label").asText().isEmpty()) step.remove("label");
    }
    if (w.getInputSchema() != null) out.set("inputs", w.getInputSchema().path("properties"));
    ArrayNode scheduled = out.putArray("scheduled_tasks");
    tasks.findByBoundWorkflowId(w.getId()).stream().filter(t -> SCHEDULED.contains(t.getStatus()))
        .forEach(t -> scheduled.add(task(t).toJson()));
    ArrayNode last = out.putArray("recent_runs");
    Instant now = clock.instant();
    runs.findRows(userId, now.minus(RUN_WINDOW), now.plusSeconds(60), PageRequest.of(0, 200)).stream()
        .filter(r -> w.getId().equals(r.workflowId())).limit(5)
        .forEach(r -> {
          ObjectNode run = last.addObject().put("run_id", r.id().toString())
              .put("status", lower(r.status())).put("started", r.createdAt().toString());
          if (r.error() != null) run.put("error", cut(r.error(), 300));
        });
    return out;
  }

  private ObjectNode runDetail(WorkflowRunEntity r) {
    ObjectNode out = Json.obj().put("module", Module.RUNS.key).put("id", r.getId().toString())
        .put("status", lower(r.getStatus())).put("started", r.getCreatedAt().toString())
        .put("trigger", nz(r.getTriggerSource()));
    workflows.findById(r.getWorkflowId()).ifPresent(w -> out.put("workflow", w.getName())
        .put("workflow_id", w.getId().toString()));
    if (r.getUpdatedAt() != null) out.put("updated", r.getUpdatedAt().toString());
    if (r.getCurrentNode() != null) out.put("current_step", r.getCurrentNode());
    if (r.getError() != null) out.put("error", cut(r.getError(), 1000));
    ArrayNode waiting = out.putArray("open_questions");
    notifications.findByRunId(r.getId()).stream().filter(Workspace::open)
        .forEach(n -> waiting.add(notification(n).toJson()));
    return out;
  }

  private static ObjectNode functionDetail(ScriptFunctionEntity f) {
    ObjectNode out = function(f, f.getUserId() == null ? "public" : "yours").toJson();
    out.put("call_as", "fn." + f.getName() + "(...)");
    if (f.getInputs() != null) out.set("inputs", f.getInputs());
    out.put("returns", nz(f.getReturnType()));
    out.put("script", cut(f.getScript(), 1500));
    return out;
  }

  private static ObjectNode agentDetail(AgentEntity a) {
    ObjectNode out = agent(a).toJson().put("key", a.getKey());
    if (a.getDomain() != null) out.put("domain", a.getDomain());
    return out;
  }

  private static ObjectNode deviceDetail(DeviceEntity d) {
    ObjectNode out = device(d).toJson().put("platform", d.getPlatform());
    if (d.getActions() != null && d.getActions().isArray()) {
      ArrayNode actions = out.putArray("actions");
      for (JsonNode a : d.getActions()) {
        if (actions.size() >= 40) break;
        actions.add(a.isTextual() ? a.asText() : a.path("id").asText(a.toString()));
      }
    }
    return out;
  }

  private static ObjectNode mcpDetail(McpServerEntity m) {
    ObjectNode out = mcp(m).toJson();
    ArrayNode tools = out.putArray("tools");
    if (m.getTools() != null) {
      for (JsonNode t : m.getTools()) {
        if (tools.size() >= 40) break;
        tools.add(t.path("name").asText(""));
      }
    }
    return out;
  }

  private ObjectNode taskDetail(ScheduledTaskEntity t) {
    ObjectNode out = task(t).toJson().put("schedule", schedule(t));
    if (t.getNextRunAt() != null) out.put("next_run", t.getNextRunAt().toString());
    if (t.getLastRunAt() != null) out.put("last_run", t.getLastRunAt().toString());
    if (t.getLastResult() != null) out.put("last_result", cut(t.getLastResult(), 500));
    if (t.getBoundWorkflowId() != null) {
      out.put("workflow_id", t.getBoundWorkflowId().toString());
      workflows.findById(t.getBoundWorkflowId()).ifPresent(w -> out.put("workflow", w.getName()));
    }
    return out;
  }

  private static ObjectNode notificationDetail(NotificationEntity n) {
    ObjectNode out = notification(n).toJson().put("body", nz(n.getBody()))
        .put("created", n.getCreatedAt().toString()).put("state", lower(n.getStatus()));
    if (n.getWorkflowId() != null) out.put("workflow_id", n.getWorkflowId().toString());
    if (n.getRunId() != null) out.put("run_id", n.getRunId().toString());
    return out;
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static String schedule(ScheduledTaskEntity t) {
    if (t.getScheduleType() == null) return "";
    return switch (t.getScheduleType()) {
      case ONE_TIME -> "once" + (t.getRunAt() == null ? "" : " at " + t.getRunAt());
      case RECURRING -> "repeats (" + nz(t.getCronOrRrule()) + ")"
          + (t.getTimezone() == null ? "" : " " + t.getTimezone());
    };
  }

  private static <E extends Enum<E>> String lower(E value) {
    return value == null ? null : value.name().toLowerCase(Locale.ROOT);
  }

  static String nz(String s) {
    return s == null ? "" : s;
  }

  static String cut(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }
}
