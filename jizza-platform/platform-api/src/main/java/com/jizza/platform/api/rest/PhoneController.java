package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.device.DeviceAgentGateway.Companion;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.device.PhoneAccess;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.dto.AutonomyRuleDto;
import com.jizza.engine.api.dto.AuditEntryDto;
import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.dto.RunDto;
import com.jizza.engine.api.dto.RunHistoryDto;
import com.jizza.engine.api.dto.UsageEntryDto;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.llm.UsageStore;
import com.jizza.engine.api.notification.Answer;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.PublicEndpoint;
import com.jizza.engine.api.util.TimeBuckets;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.platform.usecase.activity.ActivityUseCase;
import com.jizza.platform.usecase.agent.AgentAdminUseCase;
import com.jizza.platform.usecase.autonomy.AutonomyUseCase;
import com.jizza.platform.usecase.autonomy.GuardrailsUseCase;
import com.jizza.platform.usecase.device.DeviceUseCase;
import com.jizza.platform.usecase.device.PhoneAskUseCase;
import com.jizza.platform.usecase.help.HelpGuidesUseCase;
import com.jizza.platform.usecase.notification.NotificationUseCase;
import com.jizza.platform.usecase.usage.UsageUseCase;
import com.jizza.platform.usecase.workflow.RunHistoryUseCase;
import com.jizza.platform.usecase.workflow.WorkflowUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Jizza app on a person's own phone: their runs, approvals, usage, activity, devices, agents
 * and guardrails, and the few things they can do from their pocket. It is not a copy of the web
 * API: it shows what a person needs at a glance and acts only on signed requests (see
 * {@link PhoneRequests}).
 *
 * <p>Every route takes the device token. Each one calls the same use case as its {@code /api} twin,
 * as the phone's owner, so nothing here can see more than that person can on the web.
 */
@RestController
@RequestMapping("/agent/v1/me")
public class PhoneController {

  /** Who is holding this phone, and what it may do. */
  public record Me(UUID userId, UUID workspaceId, UUID deviceId, String deviceName,
                   PhoneAccess access) {}

  /** The Today screen in one call. */
  public record Today(Instant since, Waiting waiting, Runs runs, Spend spend, DeviceCount devices,
                      boolean paused) {}

  public record Waiting(int needsYou, int urgent, int unread) {}

  public record Runs(long total, long done, long failed, long running, long waiting,
                     List<RunHistoryDto.Summary> active) {}

  /**
   * @param costUnits spent in the current budget window
   * @param budgetLimit null when no budget is set
   */
  public record Spend(Instant windowStart, Instant windowEnd, BigDecimal costUnits,
                      BigDecimal budgetLimit, boolean withinBudget) {}

  public record DeviceCount(int total, int online, List<String> offline) {}

  /** A workflow as the "Run a workflow" list shows it. */
  public record WorkflowItem(UUID id, String name, String description, String status, int version,
                             JsonNode inputSchema) {}

  /** An agent as the Agents screen shows it; its instructions stay on the web. */
  public record AgentItem(UUID id, String name, String description, String status,
                          boolean enabled, String modelTier, Double monthlyCostLimit,
                          UUID parentAgentId) {}

  public record AnswerBody(Answer.Kind kind, String instruction, String feedback) {}

  public record SnoozeBody(Instant until) {}

  public record ArchiveBody(Boolean archived) {}

  public record RunBody(JsonNode input, Integer version) {}

  public record PauseBody(Boolean paused) {}

  public record ResumeBody(String instruction, Boolean approved) {}

  public record AskBody(String question, String tz) {}

  public record EnabledBody(Boolean enabled) {}

  public record Done(String message) {}

  private final PhoneRequests phones;
  private final NotificationUseCase notifications;
  private final RunHistoryUseCase history;
  private final WorkflowUseCase workflows;
  private final WorkflowEngine engine;
  private final UsageUseCase usage;
  private final ActivityUseCase activity;
  private final GuardrailsUseCase guardrails;
  private final AutonomyUseCase autonomy;
  private final DeviceUseCase devices;
  private final AgentDirectory agents;
  private final AuditLog audit;
  private final PhoneAskUseCase asking;
  private final AgentAdminUseCase agentAdmin;
  private final HelpGuidesUseCase help;

  public PhoneController(PhoneRequests phones, NotificationUseCase notifications,
                         RunHistoryUseCase history, WorkflowUseCase workflows,
                         WorkflowEngine engine, UsageUseCase usage, ActivityUseCase activity,
                         GuardrailsUseCase guardrails, AutonomyUseCase autonomy,
                         DeviceUseCase devices, AgentDirectory agents, AuditLog audit,
                         PhoneAskUseCase asking, AgentAdminUseCase agentAdmin,
                         HelpGuidesUseCase help) {
    this.phones = phones;
    this.notifications = notifications;
    this.history = history;
    this.workflows = workflows;
    this.engine = engine;
    this.usage = usage;
    this.activity = activity;
    this.guardrails = guardrails;
    this.autonomy = autonomy;
    this.devices = devices;
    this.agents = agents;
    this.audit = audit;
    this.asking = asking;
    this.agentAdmin = agentAdmin;
    this.help = help;
  }

  // ---- Reading ----------------------------------------------------------------------------------

  @PublicEndpoint
  @GetMapping
  public Me me(HttpServletRequest http) {
    Companion phone = phones.read(http);
    return new Me(phone.userId(), phone.workspaceId(), phone.deviceId(), phone.deviceName(),
        phone.access());
  }

  /** @param tz the phone's time zone, so "today" starts at the person's midnight */
  @PublicEndpoint
  @GetMapping("/today")
  public Today today(@RequestParam(required = false) String tz, HttpServletRequest http) {
    Companion phone = phones.read(http);
    UUID userId = phone.userId();
    var zone = TimeBuckets.zoneOf(tz);
    Instant since = LocalDate.now(zone).atStartOfDay(zone).toInstant();

    var waiting = notifications.summary(userId);
    var page = history.search(userId, new RunHistoryDto.Query(since, null, null, null, null, null,
        50, 0, zone));
    var byStatus = page.stats().byStatus();
    var active = page.items().stream()
        .filter(r -> r.status() == RunStatus.RUNNING || r.status() == RunStatus.WAITING
            || r.status() == RunStatus.PAUSED_WAITING_INSTRUCTION)
        .limit(10).toList();
    Runs runs = new Runs(page.stats().total(), count(byStatus, RunStatus.DONE),
        count(byStatus, RunStatus.FAILED), count(byStatus, RunStatus.RUNNING),
        count(byStatus, RunStatus.WAITING) + count(byStatus, RunStatus.PAUSED_WAITING_INSTRUCTION),
        active);

    UsageStore.Summary spent = usage.summary(userId);
    Spend spend = new Spend(spent.windowStart(), spent.windowEnd(), spent.totalCostUnits(),
        spent.budgetLimit(), spent.withinBudget());

    List<DeviceDto> all = devices.list(access(phone));
    DeviceCount count = new DeviceCount(all.size(),
        (int) all.stream().filter(DeviceDto::online).count(),
        all.stream().filter(d -> !d.online()).map(DeviceDto::name).limit(10).toList());

    return new Today(since, new Waiting(waiting.needsYou(), waiting.urgent(), waiting.unread()),
        runs, spend, count, autonomy.paused(userId));
  }

  @PublicEndpoint
  @GetMapping("/notifications")
  public List<NotificationDto> notifications(
      @RequestParam(defaultValue = "false") boolean unreadOnly, HttpServletRequest http) {
    return notifications.list(phones.read(http).userId(), unreadOnly);
  }

  @PublicEndpoint
  @GetMapping("/notifications/summary")
  public NotificationUseCase.Summary notificationSummary(HttpServletRequest http) {
    return notifications.summary(phones.read(http).userId());
  }

  @PublicEndpoint
  @GetMapping("/notifications/{id}")
  public NotificationDto notification(@PathVariable UUID id, HttpServletRequest http) {
    return notifications.get(phones.read(http).userId(), id);
  }

  @PublicEndpoint
  @GetMapping("/runs")
  public RunHistoryDto.Page runs(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) UUID workflowId,
      @RequestParam(required = false) Set<RunStatus> status,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "30") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) String tz,
      HttpServletRequest http) {
    return history.search(phones.read(http).userId(), new RunHistoryDto.Query(from, to,
        workflowId, status, null, q, Math.min(Math.max(limit, 1), 100), Math.max(offset, 0),
        TimeBuckets.zoneOf(tz)));
  }

  @PublicEndpoint
  @GetMapping("/runs/{id}/trace")
  public RunHistoryDto.Trace trace(@PathVariable UUID id, HttpServletRequest http) {
    return history.trace(phones.read(http).userId(), id);
  }

  @PublicEndpoint
  @GetMapping("/workflows")
  public List<WorkflowItem> workflows(HttpServletRequest http) {
    return workflows.list(phones.read(http).userId()).stream()
        .map(w -> new WorkflowItem(w.id(), w.name(), w.description(),
            w.status() == null ? null : w.status().name(), w.version(), w.inputSchema()))
        .toList();
  }

  @PublicEndpoint
  @GetMapping("/usage")
  public UsageStore.Summary usage(HttpServletRequest http) {
    return usage.summary(phones.read(http).userId());
  }

  @PublicEndpoint
  @GetMapping("/usage/entries")
  public List<UsageEntryDto> usageEntries(@RequestParam(defaultValue = "50") int limit,
                                          HttpServletRequest http) {
    return usage.recent(phones.read(http).userId(), Math.min(Math.max(limit, 1), 200));
  }

  @PublicEndpoint
  @GetMapping("/activity")
  public List<AuditEntryDto> activity(@RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(required = false) String action,
                                      HttpServletRequest http) {
    return activity.recent(phones.read(http).userId(), Math.min(Math.max(limit, 1), 200), action);
  }

  @PublicEndpoint
  @GetMapping("/guardrails")
  public GuardrailsUseCase.Overview guardrails(HttpServletRequest http) {
    UUID userId = phones.read(http).userId();
    return guardrails.overview(userId, autonomy.defaultRuleDescription(), autonomy.paused(userId));
  }

  @PublicEndpoint
  @GetMapping("/devices")
  public List<DeviceDto> devices(HttpServletRequest http) {
    return devices.list(access(phones.read(http)));
  }

  @PublicEndpoint
  @GetMapping("/agents")
  public List<AgentItem> agents(HttpServletRequest http) {
    return agents.list(phones.read(http).userId()).stream()
        .filter(a -> !"archived".equals(a.status()))
        .map(PhoneController::item)
        .toList();
  }

  /**
   * The Ask tab: a short answer about the person's own Jizza, from what the phone may already
   * read, on the cheap model. It changes nothing. A command it offers is only a suggestion: the app
   * confirms it with the fingerprint and sends it to the signed route for it.
   */
  /** The Help guides the web shows at /help, from the same Markdown files. */
  @PublicEndpoint
  @GetMapping("/help")
  public List<HelpGuidesUseCase.Summary> help(HttpServletRequest http) {
    phones.read(http);
    return help.list();
  }

  @PublicEndpoint
  @GetMapping("/help/{slug}")
  public HelpGuidesUseCase.Guide helpGuide(@PathVariable String slug, HttpServletRequest http) {
    phones.read(http);
    return help.get(slug);
  }

  @PublicEndpoint
  @PostMapping("/ask")
  public PhoneAskUseCase.Answer ask(@RequestBody AskBody body, HttpServletRequest http) {
    if (body == null) throw new ValidationException("Ask something first");
    Companion phone = phones.read(http);
    UUID userId = phone.userId();
    var zone = TimeBuckets.zoneOf(body.tz());
    Today today = today(body.tz(), http);

    var week = history.search(userId, new RunHistoryDto.Query(
        Instant.now().minus(java.time.Duration.ofDays(7)), null, null, null, null, null, 30, 0, zone));
    var recent = Json.mapper().createArrayNode();
    java.util.Map<UUID, String> activeRuns = new java.util.LinkedHashMap<>();
    for (RunHistoryDto.Summary r : week.items()) {
      var row = Json.obj().put("id", r.id().toString()).put("workflow", r.workflowName())
          .put("status", r.status().name()).put("startedAt", String.valueOf(r.startedAt()))
          .put("durationMs", r.durationMs());
      if (r.error() != null) row.put("error", clip(r.error(), 200));
      if (r.failedNode() != null) row.put("failedStep", r.failedNode());
      recent.add(row);
      if (r.status() == RunStatus.RUNNING || r.status() == RunStatus.WAITING
          || r.status() == RunStatus.PAUSED_WAITING_INSTRUCTION) {
        activeRuns.put(r.id(), r.workflowName() == null ? "workflow" : r.workflowName());
      }
    }

    var flows = Json.mapper().createArrayNode();
    java.util.Map<UUID, String> runnable = new java.util.LinkedHashMap<>();
    for (WorkflowDto w : workflows.list(userId)) {
      boolean active = w.status() == null || "ACTIVE".equals(w.status().name());
      JsonNode required = w.inputSchema() == null ? null : w.inputSchema().path("required");
      boolean free = required == null || !required.isArray() || required.isEmpty();
      flows.add(Json.obj().put("id", w.id().toString()).put("name", w.name())
          .put("active", active).put("runsWithoutInput", free));
      if (active && free) runnable.put(w.id(), w.name());
    }

    var waiting = Json.mapper().createArrayNode();
    notifications.list(userId, false).stream().filter(NotificationDto::open).limit(10)
        .forEach(n -> waiting.add(Json.obj().put("kind", n.kind().name())
            .put("what", clip(n.body(), 200)).put("agent", n.sourceAgentName())
            .put("since", String.valueOf(n.createdAt()))));

    var facts = Json.obj().put("timeZone", zone.getId()).put("now", Instant.now().toString());
    facts.set("today", Json.toNode(today));
    facts.set("runsLast7Days", recent);
    facts.set("activeRuns", Json.toNode(activeRuns.keySet().stream().map(UUID::toString).toList()));
    facts.set("workflows", flows);
    facts.set("waitingForYou", waiting);
    return asking.ask(userId, body.question(), facts, runnable, activeRuns);
  }

  // ---- Acting: every one of these is signed by the phone's key ----------------------------------

  /** Approve, decline or answer what a run or agent is waiting on. */
  @PublicEndpoint
  @PostMapping("/notifications/{id}/answer")
  public NotificationDto answer(@PathVariable UUID id, @RequestBody(required = false) byte[] raw,
                                HttpServletRequest http) {
    Companion phone = phones.signed(http, raw, PhoneAccess.APPROVE);
    AnswerBody body = parse(raw, AnswerBody.class);
    if (body == null || body.kind() == null) {
      throw new ValidationException("kind is required: APPROVE, DENY or SUBMIT_INSTRUCTION");
    }
    if (body.instruction() != null && body.instruction().length() > 8000) {
      throw new ValidationException("The answer is too long");
    }
    if (body.feedback() != null && body.feedback().length() > 4000) {
      throw new ValidationException("The reason is too long");
    }
    NotificationDto answered = notifications.answer(phone.userId(), id,
        new Answer(body.kind(), body.instruction(), body.feedback()));
    record(phone, "device.phone.answered", id.toString(),
        Json.obj().put("kind", body.kind().name()));
    return answered;
  }

  /**
   * Marks an item read, as opening it on the web does: the phone sends it when the person opens an
   * item from a push, so the web inbox does not still show it as new. Any access level may.
   */
  @PublicEndpoint
  @PostMapping("/notifications/{id}/read")
  public NotificationDto read(@PathVariable UUID id, @RequestBody(required = false) byte[] raw,
                              HttpServletRequest http) {
    Companion phone = phones.signed(http, raw, PhoneAccess.READ);
    notifications.markRead(phone.userId(), id);
    return notifications.get(phone.userId(), id);
  }

  /** Hide an item until later; an empty {@code until} brings it back now. */
  @PublicEndpoint
  @PostMapping("/notifications/{id}/snooze")
  public NotificationDto snooze(@PathVariable UUID id, @RequestBody(required = false) byte[] raw,
                                HttpServletRequest http) {
    Companion phone = phones.signed(http, raw, PhoneAccess.APPROVE);
    SnoozeBody body = parse(raw, SnoozeBody.class);
    return notifications.snooze(phone.userId(), id, body == null ? null : body.until());
  }

  /** Archive an item, or bring it back with {@code archived: false}, as the web inbox's "e". */
  @PublicEndpoint
  @PostMapping("/notifications/{id}/archive")
  public NotificationDto archive(@PathVariable UUID id, @RequestBody(required = false) byte[] raw,
                                 HttpServletRequest http) {
    Companion phone = phones.signed(http, raw, PhoneAccess.APPROVE);
    ArchiveBody body = parse(raw, ArchiveBody.class);
    return notifications.archive(phone.userId(), id, body == null || body.archived() == null || body.archived());
  }

  @PublicEndpoint
  @PostMapping("/workflows/{id}/run")
  public RunDto run(@PathVariable UUID id, @RequestBody(required = false) byte[] raw,
                    HttpServletRequest http) {
    Companion phone = phones.signed(http, raw, PhoneAccess.FULL);
    RunBody body = parse(raw, RunBody.class);
    WorkflowDto workflow = workflows.get(phone.userId(), id);
    int version = body == null || body.version() == null ? workflow.version() : body.version();
    JsonNode input = body == null || body.input() == null || body.input().isNull()
        ? Json.obj() : body.input();
    if (!input.isObject()) throw new ValidationException("input must be an object");
    RunDto run = engine.start(id, version, input, "manual:" + phone.userId());
    record(phone, "device.phone.run_started", workflow.name(),
        Json.obj().put("workflowId", id.toString()).put("runId", run.id().toString()));
    return run;
  }

  @PublicEndpoint
  @PostMapping("/runs/{id}/cancel")
  public Done cancel(@PathVariable UUID id, @RequestBody(required = false) byte[] raw,
                     HttpServletRequest http) {
    Companion phone = phones.signed(http, raw, PhoneAccess.FULL);
    RunDto run = engine.get(id);
    if (run == null || !phone.userId().equals(run.userId())) {
      throw new NotFoundException("Workflow run", id);
    }
    engine.cancel(id);
    record(phone, "device.phone.run_cancelled", id.toString(), Json.obj());
    return new Done("Run cancelled.");
  }

  /**
   * "Always allow" on an approval for an action: saves an allow rule for that agent and tool, as
   * the web inbox does, then approves this one. Loosening a guardrail needs full access.
   */
  @PublicEndpoint
  @PostMapping("/notifications/{id}/always-allow")
  public NotificationDto alwaysAllow(@PathVariable UUID id,
                                     @RequestBody(required = false) byte[] raw,
                                     HttpServletRequest http) {
    Companion phone = phones.signed(http, raw, PhoneAccess.FULL);
    NotificationDto item = notifications.get(phone.userId(), id);
    String tool = item.context() == null ? null : item.context().path("tool").asText(null);
    if (item.kind() != NotificationKind.PERMISSION_REQUEST || tool == null || tool.isBlank()) {
      throw new ValidationException("Only an approval for an action can be always allowed");
    }
    if (item.status() != NotificationStatus.UNREAD && item.status() != NotificationStatus.READ) {
      throw new ValidationException("This item is already answered");
    }
    autonomy.upsert(phone.userId(), new AutonomyRuleDto(null, phone.userId(),
        item.sourceAgentId(), tool, AutonomyRule.ALLOW, null, 20));
    NotificationDto answered = notifications.answer(phone.userId(), id,
        new Answer(Answer.Kind.APPROVE, null, null));
    record(phone, "device.phone.always_allowed", id.toString(), Json.obj().put("tool", tool));
    return answered;
  }

  /** Continue a run that is waiting, as the web's run history does. */
  @PublicEndpoint
  @PostMapping("/runs/{id}/resume")
  public RunDto resume(@PathVariable UUID id, @RequestBody(required = false) byte[] raw,
                       HttpServletRequest http) {
    Companion phone = phones.signed(http, raw, PhoneAccess.FULL);
    RunDto run = engine.get(id);
    if (run == null || !phone.userId().equals(run.userId())) {
      throw new NotFoundException("Workflow run", id);
    }
    ResumeBody body = parse(raw, ResumeBody.class);
    var payload = Json.obj();
    if (body != null && body.instruction() != null) {
      if (body.instruction().length() > 8000) throw new ValidationException("The answer is too long");
      payload.put("instruction", body.instruction());
    }
    if (body != null && body.approved() != null) payload.put("approved", body.approved());
    RunDto resumed = engine.resume(id, payload);
    record(phone, "device.phone.run_resumed", id.toString(), Json.obj());
    return resumed;
  }

  /**
   * Turn one agent off or on, as the switch on the web's Agents page. Like Pause all, turning off
   * needs approve access and turning on needs full access.
   */
  @PublicEndpoint
  @PostMapping("/agents/{id}/enabled")
  public AgentItem setAgentEnabled(@PathVariable UUID id, @RequestBody(required = false) byte[] raw,
                                   HttpServletRequest http) {
    EnabledBody body = parse(raw, EnabledBody.class);
    if (body == null || body.enabled() == null) throw new ValidationException("enabled is required");
    Companion phone = phones.signed(http, raw,
        body.enabled() ? PhoneAccess.FULL : PhoneAccess.APPROVE);
    AgentDto agent = agentAdmin.setEnabled(phone.userId(), id, body.enabled());
    record(phone, body.enabled() ? "device.phone.agent_enabled" : "device.phone.agent_disabled",
        agent.name(), Json.obj().put("agentId", id.toString()));
    return item(agent);
  }

  /**
   * Pause all is the emergency stop, so a phone that may approve can pull it. Starting everything
   * again needs full access.
   */
  @PublicEndpoint
  @PostMapping("/pause")
  public Done pause(@RequestBody(required = false) byte[] raw, HttpServletRequest http) {
    PauseBody body = parse(raw, PauseBody.class);
    if (body == null || body.paused() == null) throw new ValidationException("paused is required");
    Companion phone = phones.signed(http, raw,
        body.paused() ? PhoneAccess.APPROVE : PhoneAccess.FULL);
    autonomy.setPaused(phone.userId(), body.paused());
    record(phone, body.paused() ? "device.phone.paused_all" : "device.phone.resumed_all", null,
        Json.obj());
    return new Done(body.paused()
        ? "Everything is paused. Nothing runs until you resume."
        : "Resumed. Agents and workflows run again.");
  }

  // ---- Helpers ----------------------------------------------------------------------------------

  private void record(Companion phone, String action, String target, com.fasterxml.jackson.databind.node.ObjectNode detail) {
    audit.record(phone.userId(), ActorType.USER, null, action, target,
        detail.put("deviceId", phone.deviceId().toString()).put("device", phone.deviceName()));
  }

  private static String clip(String text, int max) {
    if (text == null) return null;
    return text.length() > max ? text.substring(0, max) + "…" : text;
  }

  private static Access access(Companion phone) {
    return new Access(phone.userId(), phone.workspaceId());
  }

  private static long count(java.util.Map<RunStatus, Long> byStatus, RunStatus status) {
    if (byStatus == null) return 0;
    Long n = byStatus.get(status);
    return n == null ? 0 : n;
  }

  private static AgentItem item(AgentDto a) {
    return new AgentItem(a.id(), a.name(), a.description(), a.status(), a.enabled(),
        a.modelTier(), a.monthlyCostLimit(), a.parentAgentId());
  }

  private static <T> T parse(byte[] raw, Class<T> type) {
    if (raw == null || raw.length == 0) return null;
    try {
      return Json.mapper().readValue(raw, type);
    } catch (java.io.IOException e) {
      throw new ValidationException("The request body is not valid JSON for this call");
    }
  }
}
