package com.jizza.platform.usecase.autonomy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.AutonomyPolicyStore;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.autonomy.GuardrailContext;
import com.jizza.engine.api.autonomy.GuardrailSettingsStore;
import com.jizza.engine.api.autonomy.GuardrailSettingsStore.QuietHours;
import com.jizza.engine.api.connector.ToolCatalog;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.dto.AutonomyRuleDto;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Guardrails screen: what each agent may do on its own, what a workflow's steps will do, and
 * "what would happen if" checks. Everything it shows comes from the gate itself, so the screen can
 * never disagree with what actually runs.
 */
@Service
public class GuardrailsUseCase {

  /** The effective mode for one kind of action, and the rule that decides it. */
  public record Cell(AutonomyRule rule, UUID policyId, String matched, boolean locked) {}

  public record AgentGuardrails(UUID id, String key, String name, boolean orchestrator,
                                boolean enabled, Map<ActionType, Cell> modes, boolean custom,
                                UUID parentAgentId, String status) {}

  public record Overview(List<AutonomyRuleDto> rules, List<AgentGuardrails> agents,
                         String defaultRule, boolean paused, QuietHours quietHours) {}

  /** What one maintaining agent's guardrails decide for a step. */
  public record AgentDecision(UUID agentId, String agentName, Decision decision) {}

  /**
   * One step of a workflow as the guardrails will judge it on the current version. {@code agentId}
   * and {@code decision} are the lead maintainer's, which is whose guardrails a run follows;
   * {@code perAgent} has the same step under each maintaining agent.
   */
  public record Step(String nodeId, String label, NodeType type, UUID agentId, String agentName,
                     String toolName, ActionType actionType, Decision decision,
                     List<AgentDecision> perAgent) {}

  /** An agent that maintains the workflow; the first one leads. */
  public record Maintainer(UUID id, String name) {}

  public record WorkflowGuardrails(UUID workflowId, String name, int version, UUID owningAgentId,
                                   List<Step> steps, List<AutonomyRuleDto> rules,
                                   List<Maintainer> maintainers) {}

  private final AutonomyGate gate;
  private final AutonomyPolicyStore policies;
  private final GuardrailSettingsStore settings;
  private final AgentDirectory agents;
  private final WorkflowRegistry workflows;
  private final ToolCatalog tools;
  private final AuditLog audit;
  private final AutonomyUseCase autonomy;

  /** MCP servers, for the guardrails of a workflow's MCP tool steps; absent in narrow harnesses. */
  private org.springframework.beans.factory.ObjectProvider<com.jizza.engine.api.mcp.McpServers> mcpServers;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setMcpServers(
      org.springframework.beans.factory.ObjectProvider<com.jizza.engine.api.mcp.McpServers> mcpServers) {
    this.mcpServers = mcpServers;
  }

  /** Device actions, for the guardrails of a workflow's device and web steps. */
  private org.springframework.beans.factory.ObjectProvider<com.jizza.engine.api.device.DeviceActionCatalog> deviceActions;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setDeviceActions(
      org.springframework.beans.factory.ObjectProvider<com.jizza.engine.api.device.DeviceActionCatalog> deviceActions) {
    this.deviceActions = deviceActions;
  }

  public GuardrailsUseCase(AutonomyGate gate, AutonomyPolicyStore policies,
                           GuardrailSettingsStore settings, AgentDirectory agents,
                           WorkflowRegistry workflows, ToolCatalog tools, AuditLog audit,
                           AutonomyUseCase autonomy) {
    this.autonomy = autonomy;
    this.gate = gate;
    this.policies = policies;
    this.settings = settings;
    this.agents = agents;
    this.workflows = workflows;
    this.tools = tools;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public Overview overview(UUID userId, String defaultRule, boolean paused) {
    List<AutonomyRuleDto> rules = policies.list(userId);
    List<AgentGuardrails> grid = new ArrayList<>();
    for (AgentDto agent : agents.list(userId)) {
      if ("archived".equals(agent.status())) continue;
      Map<ActionType, Cell> modes = new LinkedHashMap<>();
      for (ActionType type : ActionType.values()) {
        // A stand-in call of that kind, judged as the agent would be outside any workflow. A rule
        // with conditions shows as its mode; the conditions are on the rule itself.
        // A draft shows the rules it will start with once it is activated.
        Decision d = com.jizza.engine.api.agent.TrialRun.run("draft".equals(agent.status()),
            () -> gate.evaluate(new GuardrailContext(agent.id(), type.token(), null, null, null,
                null, true, type)));
        modes.put(type, new Cell(d.rule(), d.policyId(), d.matchedPolicy(),
            rules.stream().anyMatch(r -> r.locked() && r.id().equals(d.policyId()))));
      }
      grid.add(new AgentGuardrails(agent.id(), agent.key(), agent.name(), agent.orchestrator(),
          agent.enabled(), modes, agent.custom(), agent.parentAgentId(), agent.status()));
    }
    return new Overview(rules, grid, defaultRule, paused,
        settings.quietHours(userId).orElse(null));
  }

  @Transactional(readOnly = true)
  public WorkflowGuardrails workflow(UUID userId, UUID workflowId) {
    WorkflowDto workflow = ownWorkflow(userId, workflowId);
    List<UUID> chosen = maintainersOf(userId, workflow);
    List<Step> steps = new ArrayList<>();
    for (WorkflowNode node : WorkflowGraph.from(workflow.graph()).nodes()) {
      if (node.type() == NodeType.AGENT_STEP) {
        UUID agentId = uuid(node.config() == null ? null : node.config().path("agentId"));
        // An agent step chooses its tools as it runs; each is judged then, inside this workflow.
        steps.add(new Step(node.id(), node.label(), node.type(), agentId,
            agentId == null ? null : agents.nameOf(agentId), null, null, null, List.of()));
        continue;
      }
      Judged judged = judgeable(node);
      if (judged == null) continue;
      List<AgentDecision> perAgent = new ArrayList<>();
      for (UUID actor : chosen) {
        Decision d = judged.missing() != null ? Decision.deny(judged.missing(), "no-tool")
            : gate.evaluate(new GuardrailContext(actor, judged.toolRef(), null, workflow.id(),
                workflow.version(), node.id(), true, judged.knownType())
                .withConnection(judged.connectionId()));
        perAgent.add(new AgentDecision(actor, agents.nameOf(actor), d));
      }
      AgentDecision lead = perAgent.get(0);
      steps.add(new Step(node.id(), node.label(), node.type(), lead.agentId(), lead.agentName(),
          judged.missing() != null ? null : judged.toolName(),
          judged.missing() != null ? null : lead.decision().actionType(), lead.decision(),
          perAgent));
    }
    List<AutonomyRuleDto> rules = policies.list(userId).stream()
        .filter(rule -> workflowId.equals(rule.workflowId())).toList();
    List<Maintainer> maintainers = workflows.maintainers(workflowId).stream()
        .filter(chosen::contains)
        .map(id -> new Maintainer(id, agents.nameOf(id))).toList();
    return new WorkflowGuardrails(workflow.id(), workflow.name(), workflow.version(),
        workflow.owningAgentId(), steps, rules, maintainers);
  }

  /**
   * Chooses the agents that maintain a workflow, lead first. The lead's guardrails judge each run;
   * the step rules set for each agent stay with that agent. No new version is made.
   */
  @Transactional
  public WorkflowGuardrails setMaintainers(UUID userId, UUID workflowId, List<UUID> agentIds) {
    ownWorkflow(userId, workflowId);
    List<UUID> ids = agentIds == null ? List.of() : agentIds.stream().distinct().toList();
    if (ids.size() > 20) throw new com.jizza.engine.api.error.ValidationException("Choose at most 20 agents");
    for (UUID id : ids) {
      agents.byId(id).filter(a -> a.userId().equals(userId) && !"archived".equals(a.status()))
          .orElseThrow(() -> new NotFoundException("Agent", id));
    }
    workflows.setMaintainers(workflowId, ids);
    audit.record(userId, ActorType.USER, ids.isEmpty() ? null : ids.get(0),
        "workflow.maintainers_changed", workflowId.toString(),
        Json.obj().put("count", ids.size()));
    return workflow(userId, workflowId);
  }

  /** The maintainers that still are the person's agents, else the owner or Jizza, lead first. */
  private List<UUID> maintainersOf(UUID userId, WorkflowDto workflow) {
    List<UUID> chosen = workflows.maintainers(workflow.id()).stream()
        .filter(id -> agents.byId(id).filter(a -> a.userId().equals(userId)
            && !"archived".equals(a.status())).isPresent())
        .toList();
    if (!chosen.isEmpty()) return chosen;
    return List.of(workflow.owningAgentId() != null ? workflow.owningAgentId()
        : agents.orchestrator(userId).id());
  }

  /** How the gate names a step's call; {@code missing} says why it can't be judged. */
  private record Judged(String toolName, String toolRef, ActionType knownType, UUID connectionId,
                        String missing) {}

  private Judged judgeable(WorkflowNode node) {
    if (node.type() == NodeType.CONNECTOR_TOOL) {
      GeneratedToolDto tool = toolOf(node);
      if (tool == null) return new Judged(null, null, null, null, "The tool this step calls no longer exists");
      return new Judged(tool.name(), tool.name(), null, tool.connectionId(), null);
    }
    if (node.type() == NodeType.MCP_TOOL) {
      var tool = mcpToolOf(node);
      if (tool == null) {
        return new Judged(null, null, null, null, "The MCP tool this step calls no longer exists");
      }
      return new Judged(tool.toolName(), tool.toolName(), tool.actionType(), null, null);
    }
    if (node.type() == NodeType.DEVICE_ACTION) {
      var catalog = deviceActions == null ? null : deviceActions.getIfAvailable();
      String action = node.config() == null ? null : node.config().path("action").asText(null);
      if (catalog == null || action == null) return null;
      var spec = catalog.resolve(action, node.config().path("version").asInt(0)).orElse(null);
      if (spec == null) return new Judged(null, null, null, null, "Devices have no action called '" + action + "'");
      // The device is only known once the run has opened its browser; a step rule matches any.
      String ref = "device_any__" + spec.action().replaceAll("[^A-Za-z0-9]", "_");
      return new Judged(spec.title(), ref, spec.actionType(), null, null);
    }
    return null;
  }

  private com.jizza.engine.api.mcp.McpToolDto mcpToolOf(WorkflowNode node) {
    var store = mcpServers == null ? null : mcpServers.getIfAvailable();
    UUID serverId = uuid(node.config() == null ? null : node.config().path("serverId"));
    if (store == null || serverId == null) return null;
    String name = node.config().path("tool").asText(null);
    return store.find(serverId).flatMap(s -> s.tools().stream()
        .filter(t -> t.name().equals(name)).findFirst()).orElse(null);
  }

  /** What would happen if an agent made this call now. Reads the rules; spends nothing. */
  @Transactional(readOnly = true)
  public Decision simulate(UUID userId, UUID agentId, String toolRef, JsonNode args,
                           UUID workflowId, String nodeId) {
    AgentDto agent = agents.byId(agentId).filter(a -> a.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Agent", agentId));
    Integer version = workflowId == null ? null : ownWorkflow(userId, workflowId).version();
    return gate.evaluate(new GuardrailContext(agent.id(), toolRef, args == null ? Json.obj() : args,
        workflowId, version, workflowId == null ? null : nodeId, false, null));
  }

  /**
   * Saves a rule for one workflow, or one of its steps, on the version the user is looking at. A
   * later edit makes a new version, which the rule then no longer covers, so a changed workflow
   * is never run on permissions granted to the old one.
   */
  @Transactional
  public AutonomyRuleDto upsertForWorkflow(UUID userId, UUID workflowId, AutonomyRuleDto rule) {
    WorkflowDto workflow = ownWorkflow(userId, workflowId);
    AutonomyRuleDto scoped = new AutonomyRuleDto(rule.id(), userId, rule.agentId(), rule.toolRef(),
        rule.rule(), rule.limits(), rule.precedence(), workflow.id(), workflow.version(),
        rule.nodeId(), rule.locked());
    return autonomy.upsert(userId, scoped);
  }

  @Transactional(readOnly = true)
  public List<AutonomyRuleDto> organizationLocks(UUID workspaceId) {
    return policies.listForWorkspace(workspaceId);
  }

  @Transactional
  public AutonomyRuleDto lockForOrganization(UUID userId, UUID workspaceId, AutonomyRuleDto rule) {
    AutonomyRuleDto saved = policies.upsertForWorkspace(userId, workspaceId, rule);
    audit.record(userId, ActorType.USER, null, "guardrail.organization_locked", saved.toolRef(),
        Json.obj().put("workspaceId", workspaceId.toString())
            .put("rule", saved.rule().name().toLowerCase(Locale.ROOT)));
    return saved;
  }

  @Transactional
  public void unlockForOrganization(UUID userId, UUID workspaceId, UUID policyId) {
    policies.listForWorkspace(workspaceId).stream()
        .filter(rule -> rule.id().equals(policyId))
        .findFirst()
        .ifPresent(rule -> {
          policies.deleteForWorkspace(workspaceId, policyId);
          audit.record(userId, ActorType.USER, null, "guardrail.organization_unlocked",
              rule.toolRef(), Json.obj().put("workspaceId", workspaceId.toString()));
        });
  }

  @Transactional
  public QuietHours saveQuietHours(UUID userId, QuietHours hours) {
    QuietHours saved = settings.saveQuietHours(userId, hours);
    audit.record(userId, ActorType.USER, null, "guardrail.quiet_hours_changed", null,
        Json.obj().put("start", saved.start() == null ? null : saved.start().toString())
            .put("end", saved.end() == null ? null : saved.end().toString())
            .put("timeZone", saved.timeZone()));
    return saved;
  }

  private WorkflowDto ownWorkflow(UUID userId, UUID workflowId) {
    return workflows.find(workflowId).filter(w -> w.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Workflow", workflowId));
  }

  private GeneratedToolDto toolOf(WorkflowNode node) {
    UUID toolId = uuid(node.config() == null ? null : node.config().path("toolId"));
    return toolId == null ? null : tools.find(toolId).orElse(null);
  }

  private static UUID uuid(JsonNode value) {
    if (value == null || !value.isTextual()) return null;
    try {
      return UUID.fromString(value.asText());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
