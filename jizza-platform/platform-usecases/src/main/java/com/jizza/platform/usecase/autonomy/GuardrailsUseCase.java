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
                                boolean enabled, Map<ActionType, Cell> modes) {}

  public record Overview(List<AutonomyRuleDto> rules, List<AgentGuardrails> agents,
                         String defaultRule, boolean paused, QuietHours quietHours) {}

  /** One step of a workflow as the guardrails will judge it on the current version. */
  public record Step(String nodeId, String label, NodeType type, UUID agentId, String agentName,
                     String toolName, ActionType actionType, Decision decision) {}

  public record WorkflowGuardrails(UUID workflowId, String name, int version, UUID owningAgentId,
                                   List<Step> steps, List<AutonomyRuleDto> rules) {}

  private final AutonomyGate gate;
  private final AutonomyPolicyStore policies;
  private final GuardrailSettingsStore settings;
  private final AgentDirectory agents;
  private final WorkflowRegistry workflows;
  private final ToolCatalog tools;
  private final AuditLog audit;
  private final AutonomyUseCase autonomy;

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
      Map<ActionType, Cell> modes = new LinkedHashMap<>();
      for (ActionType type : ActionType.values()) {
        // A stand-in call of that kind, judged as the agent would be outside any workflow. A rule
        // with conditions shows as its mode; the conditions are on the rule itself.
        Decision d = gate.evaluate(new GuardrailContext(agent.id(), type.token(), null, null, null,
            null, true, type));
        modes.put(type, new Cell(d.rule(), d.policyId(), d.matchedPolicy(),
            rules.stream().anyMatch(r -> r.locked() && r.id().equals(d.policyId()))));
      }
      grid.add(new AgentGuardrails(agent.id(), agent.key(), agent.name(), agent.orchestrator(),
          agent.enabled(), modes));
    }
    return new Overview(rules, grid, defaultRule, paused,
        settings.quietHours(userId).orElse(null));
  }

  @Transactional(readOnly = true)
  public WorkflowGuardrails workflow(UUID userId, UUID workflowId) {
    WorkflowDto workflow = ownWorkflow(userId, workflowId);
    UUID jizza = agents.orchestrator(userId).id();
    List<Step> steps = new ArrayList<>();
    for (WorkflowNode node : WorkflowGraph.from(workflow.graph()).nodes()) {
      if (node.type() == NodeType.CONNECTOR_TOOL) {
        GeneratedToolDto tool = toolOf(node);
        UUID actor = workflow.owningAgentId() != null ? workflow.owningAgentId() : jizza;
        if (tool == null) {
          steps.add(new Step(node.id(), node.label(), node.type(), actor, agents.nameOf(actor),
              null, null, Decision.deny("The tool this step calls no longer exists", "no-tool")));
          continue;
        }
        GuardrailContext ctx = new GuardrailContext(actor, tool.name(), null, workflow.id(),
            workflow.version(), node.id(), true, null).withConnection(tool.connectionId());
        Decision d = gate.evaluate(ctx);
        steps.add(new Step(node.id(), node.label(), node.type(), actor, agents.nameOf(actor),
            tool.name(), d.actionType(), d));
      } else if (node.type() == NodeType.AGENT_STEP) {
        UUID agentId = uuid(node.config() == null ? null : node.config().path("agentId"));
        // An agent step chooses its tools as it runs; each is judged then, inside this workflow.
        steps.add(new Step(node.id(), node.label(), node.type(), agentId,
            agentId == null ? null : agents.nameOf(agentId), null, null, null));
      }
    }
    List<AutonomyRuleDto> rules = policies.list(userId).stream()
        .filter(rule -> workflowId.equals(rule.workflowId())).toList();
    return new WorkflowGuardrails(workflow.id(), workflow.name(), workflow.version(),
        workflow.owningAgentId(), steps, rules);
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
