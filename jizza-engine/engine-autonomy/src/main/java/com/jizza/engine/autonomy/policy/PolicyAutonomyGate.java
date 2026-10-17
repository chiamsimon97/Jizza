package com.jizza.engine.autonomy.policy;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.autonomy.GuardrailContext;
import com.jizza.engine.api.autonomy.JizzaReviewer;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.autonomy.guardrail.GuardrailConditions;
import com.jizza.engine.autonomy.guardrail.GuardrailLedger;
import com.jizza.engine.persistence.entity.AgentCapabilityEntity;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.AutonomyPolicyEntity;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.entity.ServiceActionEntity;
import com.jizza.engine.persistence.repository.AgentCapabilityRepository;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.AutonomyPolicyRepository;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.ServiceActionRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The guardrails (R41), consulted before every tool call and every autonomous run.
 *
 * <p><b>What a rule matches.</b> A rule matches on agent, tool and, optionally, one workflow
 * version and one of its steps. {@code tool_ref} may be an exact tool name, a prefix glob
 * ({@code gmail_*}), an action-type token ({@code *:read}, {@code *:create}, {@code *:update},
 * {@code *:delete}, {@code *:send}, {@code *:money}, {@code *:draft}), {@code *:side-effecting}
 * for anything that changes the outside world, or {@code *} for everything.
 *
 * <p><b>Which rule decides.</b> The most specific scope wins: a workflow step, then the workflow,
 * then the agent, then the account. Within a scope the higher precedence wins, and between rules of
 * equal precedence the more specific tool reference. So an agent's cautious default can be relaxed
 * for one workflow the user approved, and only there, and a rule the user ranked higher is never
 * beaten by a narrower default.
 *
 * <p><b>What can never be loosened.</b> A locked rule is a ceiling: whatever decides, the answer
 * is at least as strict as every locked rule that matches. A looser rule (auto, or let Jizza
 * decide) whose conditions are not met does not apply; the call falls through to the next rule and
 * is never treated as anything looser than ask.
 *
 * <p>When nothing matches at all the answer is the Charter's conservative default, in code rather
 * than in data: reading and Jizza's own records are automatic, anything that changes the outside
 * world asks. Deleting every rule therefore makes the system safer, not permissive.
 */
@Service
public class PolicyAutonomyGate implements AutonomyGate {

  private static final Logger log = LoggerFactory.getLogger(PolicyAutonomyGate.class);

  // Re-exported from the interface so the matching code below reads without a qualifier; the
  // strings themselves are part of the published contract, not this implementation.
  public static final String ANY = AutonomyGate.ANY;
  public static final String ANY_READ = AutonomyGate.ANY_READ;
  public static final String ANY_SIDE_EFFECTING = AutonomyGate.ANY_SIDE_EFFECTING;

  /** What each built-in tool does. Anything not here, and not a known connector action, is unknown. */
  static final Map<String, ActionType> BUILTINS = Map.ofEntries(
      Map.entry("knowledge_search", ActionType.READ),
      Map.entry("knowledge_get", ActionType.READ),
      Map.entry("memory_recall", ActionType.READ),
      Map.entry("delegate_to_specialist", ActionType.READ),
      Map.entry("delegate_to_specialists", ActionType.READ),
      // Speaking to the user, or asking them, only ever reaches the user.
      Map.entry("notify_user", ActionType.READ),
      Map.entry("request_permission", ActionType.READ),
      Map.entry("propose_workflow", ActionType.READ),
      Map.entry("raise_signal", ActionType.READ),
      // Jizza steering the user's own screen: opening pages and forms, asking, theming.
      Map.entry("app_navigate", ActionType.READ),
      Map.entry("app_open_form", ActionType.READ),
      Map.entry("app_ask_user", ActionType.READ),
      Map.entry("app_set_theme", ActionType.READ),
      Map.entry("app_propose_workflow", ActionType.READ),
      Map.entry("app_propose_agent", ActionType.READ),
      // These change only Jizza's own records.
      Map.entry("knowledge_upsert", ActionType.DRAFT),
      Map.entry("memory_remember", ActionType.DRAFT),
      Map.entry("memory_describe", ActionType.DRAFT),
      Map.entry("memory_retire", ActionType.DRAFT),
      Map.entry("create_plan", ActionType.DRAFT),
      Map.entry("save_instruction", ActionType.DRAFT),
      // Starting work that may do anything: a whole workflow, or a job for later.
      Map.entry("schedule_task", ActionType.CREATE));

  private final AutonomyPolicyRepository policies;
  private final AgentRepository agents;
  private final GeneratedToolRepository tools;
  private final GuardrailConditions conditions;
  private final AgentCapabilityRepository capabilities;
  private final ServiceActionRepository actions;
  private final ConnectionRepository connections;
  private final GuardrailLedger ledger;
  private final ObjectProvider<JizzaReviewer> reviewer;
  private final AuditLog audit;

  @Autowired
  public PolicyAutonomyGate(AutonomyPolicyRepository policies, AgentRepository agents,
                            GeneratedToolRepository tools, GuardrailConditions conditions,
                            AgentCapabilityRepository capabilities, ServiceActionRepository actions,
                            ConnectionRepository connections, GuardrailLedger ledger,
                            ObjectProvider<JizzaReviewer> reviewer, AuditLog audit) {
    this.policies = policies;
    this.agents = agents;
    this.tools = tools;
    this.conditions = conditions;
    this.capabilities = capabilities;
    this.actions = actions;
    this.connections = connections;
    this.ledger = ledger;
    this.reviewer = reviewer;
    this.audit = audit;
  }

  /** For tests that only exercise matching: no budgets, no connector actions, no Jizza review. */
  public PolicyAutonomyGate(AutonomyPolicyRepository policies, AgentRepository agents,
                            GeneratedToolRepository tools) {
    this(policies, agents, tools, new GuardrailConditions(), null, null, null, null, null, null);
  }

  @Override
  public Decision evaluate(UUID agentId, String toolRef, com.fasterxml.jackson.databind.JsonNode args) {
    return evaluate(GuardrailContext.of(agentId, toolRef, args));
  }

  @Override
  public Decision evaluate(GuardrailContext ctx) {
    Optional<AgentEntity> found = agents.findById(ctx.agentId());
    if (found.isEmpty()) return Decision.deny("Unknown agent", "no-agent");
    AgentEntity agent = found.get();
    // While a draft or paused agent is being tried out, it is judged by its own rules.
    boolean trial = com.jizza.engine.api.agent.TrialRun.active()
        && !"archived".equals(agent.getStatus());
    if (!agent.isEnabled() && !trial) {
      return Decision.deny("Agent " + agent.getName() + " is disabled", "agent-disabled");
    }
    if (ctx.toolRef() == null) return Decision.allow("no-tool");

    Resolved resolved = resolve(agent, ctx.toolRef(), ctx.knownActionType());
    ActionType type = resolved.type();
    List<AutonomyPolicyEntity> candidates =
        policies.findByUserIdOrderByPrecedenceDesc(agent.getUserId()).stream()
            // An organization's ceilings are stored with the admin who set them; they bind
            // through the organization's connectors below, never as that admin's own rules.
            .filter(policy -> policy.getWorkspaceId() == null)
            .filter(policy -> matches(policy, ctx, type))
            .sorted(Comparator.comparingInt(PolicyAutonomyGate::scopeTier)
                .thenComparingInt(AutonomyPolicyEntity::getPrecedence)
                .thenComparingInt(policy -> specificity(policy.getToolRef()))
                .reversed())
            .toList();

    AutonomyRule ceiling = null;
    String ceilingLabel = null;
    for (AutonomyPolicyEntity policy : candidates) {
      if (policy.isLocked() && (ceiling == null
          || policy.getRule().strictness() > ceiling.strictness())) {
        ceiling = policy.getRule();
        ceilingLabel = describe(policy);
      }
    }
    // A manager's locked rules cap its whole team: nothing below it may be looser.
    List<AutonomyPolicyEntity> all = null;
    for (AgentEntity manager : managersOf(agent)) {
      // Jizza's own locks stay its own; a rule for everyone is a global rule.
      if (manager.isOrchestrator()) continue;
      if (all == null) all = policies.findByUserIdOrderByPrecedenceDesc(agent.getUserId());
      for (AutonomyPolicyEntity policy : all) {
        if (!policy.isLocked() || policy.getWorkspaceId() != null || policy.getWorkflowId() != null
            || !manager.getId().equals(policy.getAgentId())) {
          continue;
        }
        if (!toolMatches(policy.getToolRef(), ctx.toolRef(), type)) continue;
        if (ceiling == null || policy.getRule().strictness() > ceiling.strictness()) {
          ceiling = policy.getRule();
          ceilingLabel = "team:" + manager.getName() + ":"
              + (policy.getToolRef() == null ? ANY : policy.getToolRef());
        }
      }
    }
    UUID connectionId = ctx.connectionId() != null ? ctx.connectionId() : resolved.connectionId();
    for (AutonomyPolicyEntity policy : organizationRules(connectionId)) {
      if (!toolMatches(policy.getToolRef(), ctx.toolRef(), type)) continue;
      if (ceiling == null || policy.getRule().strictness() > ceiling.strictness()) {
        ceiling = policy.getRule();
        ceilingLabel = describe(policy);
      }
    }

    String fellThrough = null;
    for (AutonomyPolicyEntity policy : candidates) {
      boolean looser = policy.getRule().strictness() < AutonomyRule.ASK.strictness();
      if (looser && !ctx.probe() && GuardrailConditions.hasConditions(policy.getLimits())) {
        Optional<String> unmet = unmet(policy, ctx);
        if (unmet.isPresent()) {
          if (fellThrough == null) fellThrough = unmet.get();
          continue;
        }
      }
      Decision decision = fromRule(policy, ctx, type, agent);
      return finish(decision, fellThrough, ceiling, ceilingLabel, agent, type, policy);
    }

    Decision fallback = type.sideEffecting()
        ? new Decision(AutonomyRule.ASK,
            "This changes something outside Jizza, so it needs your approval",
            "default-conservative", null, type, false)
        : new Decision(AutonomyRule.ALLOW, "allowed by default", "default-conservative", null,
            type, false);
    return finish(fallback, fellThrough, ceiling, ceilingLabel, agent, type, null);
  }

  /**
   * Evaluates, then settles what evaluation leaves open: a decision left to Jizza is reviewed, and
   * an allowed call that spends from a budget is counted against it under a lock.
   */
  @Override
  public Decision decide(GuardrailContext ctx) {
    Decision decision = evaluate(ctx);
    if (ctx.probe()) return decision;

    if (decision.needsJizza()) {
      decision = review(ctx, decision);
    }
    if (decision.allowed() && decision.budgeted() && decision.policyId() != null
        && ledger != null) {
      UUID userId = agents.findById(ctx.agentId()).map(AgentEntity::getUserId).orElse(null);
      Optional<String> over = ledger.reserve(decision.policyId(), userId, ctx.toolRef(),
          ctx.args());
      if (over.isPresent()) return decision.withRule(AutonomyRule.ASK, over.get());
    }
    return decision;
  }

  @Override
  public boolean agentEnabled(UUID agentId) {
    return agents.findById(agentId).map(AgentEntity::isEnabled).orElse(false);
  }

  /** What a tool does, as the guardrails judge it. Public so settings screens can show it. */
  @Override
  public ActionType actionTypeOf(UUID agentId, String toolRef) {
    return agents.findById(agentId)
        .map(agent -> actionTypeOf(agent, toolRef, null))
        .orElse(ActionType.MONEY);
  }

  ActionType actionTypeOf(AgentEntity agent, String toolRef, ActionType known) {
    return resolve(agent, toolRef, known).type();
  }

  /** What a tool does, and whose connector it runs on when it is one. */
  record Resolved(ActionType type, UUID connectionId) {}

  private Resolved resolve(AgentEntity agent, String toolRef, ActionType known) {
    ActionType builtin = BUILTINS.get(toolRef);
    if (builtin != null) return new Resolved(builtin, null);
    // A skill runs a whole workflow, which may do anything; its own steps are judged as they run.
    if (toolRef.startsWith("skill_")) return new Resolved(ActionType.CREATE, null);

    Optional<Resolved> granted = grantedTool(agent, toolRef);
    if (granted.isPresent()) {
      return known == null ? granted.get() : new Resolved(known, granted.get().connectionId());
    }
    if (known != null) return new Resolved(known, null);

    return tools.findEnabledForUser(agent.getUserId()).stream()
        .filter(tool -> tool.getName().equals(toolRef))
        .findFirst()
        .map(tool -> new Resolved(tool.effectiveActionType(), tool.getConnectionId()))
        // An unknown tool is treated as the most consequential kind: unrecognised is not the same
        // as harmless.
        .orElse(new Resolved(ActionType.MONEY, null));
  }

  /** The agent's managers, nearest first. Cycle-safe. */
  private List<AgentEntity> managersOf(AgentEntity agent) {
    List<AgentEntity> chain = new java.util.ArrayList<>();
    java.util.Set<UUID> seen = new java.util.HashSet<>();
    seen.add(agent.getId());
    UUID next = agent.getParentAgentId();
    while (next != null && seen.add(next) && chain.size() < 16) {
      AgentEntity manager = agents.findById(next).orElse(null);
      if (manager == null) break;
      chain.add(manager);
      next = manager.getParentAgentId();
    }
    return chain;
  }

  /** The locked rules of the organization that owns a connector, if any. */
  private List<AutonomyPolicyEntity> organizationRules(UUID connectionId) {
    if (connectionId == null || connections == null) return List.of();
    UUID workspaceId = connections.findById(connectionId)
        .map(ConnectionEntity::getWorkspaceId).orElse(null);
    if (workspaceId == null) return List.of();
    return policies.findByWorkspaceId(workspaceId);
  }

  /** A connector tool or service action the agent holds, by the name the model sees. */
  private Optional<Resolved> grantedTool(AgentEntity agent, String toolRef) {
    if (capabilities == null) return Optional.empty();
    List<UUID> toolIds = capabilities
        .findByAgentIdAndCapabilityType(agent.getId(), CapabilityType.GENERATED_TOOL).stream()
        .map(AgentCapabilityEntity::getCapabilityRef).toList();
    if (!toolIds.isEmpty()) {
      Optional<Resolved> direct = tools.findAllById(toolIds).stream()
          .filter(tool -> tool.getName().equals(toolRef))
          .findFirst()
          .map(tool -> new Resolved(tool.effectiveActionType(), tool.getConnectionId()));
      if (direct.isPresent()) return direct;
    }
    if (actions == null || connections == null || !toolRef.contains("__")) return Optional.empty();
    List<UUID> actionIds = capabilities
        .findByAgentIdAndCapabilityType(agent.getId(), CapabilityType.SERVICE_ACTION).stream()
        .map(AgentCapabilityEntity::getCapabilityRef).toList();
    if (actionIds.isEmpty()) return Optional.empty();
    for (ServiceActionEntity action : actions.findAllById(actionIds)) {
      ConnectionEntity service = connections.findById(action.getConnectionId()).orElse(null);
      if (service == null) continue;
      if (!serviceToolName(service.getSlug(), action.getName()).equals(toolRef)) continue;
      ActionType actionType = tools.findById(action.getApiId())
          .map(GeneratedToolEntity::effectiveActionType).orElse(ActionType.MONEY);
      return Optional.of(new Resolved(actionType, service.getId()));
    }
    return Optional.empty();
  }

  /** Mirrors the name a service action is offered under: the slug and action, within 64 chars. */
  static String serviceToolName(String slug, String actionName) {
    String name = slug + "__" + actionName;
    return name.length() <= 64 ? name : name.substring(0, 64);
  }

  private Decision review(GuardrailContext ctx, Decision decision) {
    JizzaReviewer jizza = reviewer == null ? null : reviewer.getIfAvailable();
    if (jizza == null) {
      return decision.withRule(AutonomyRule.ASK,
          "Jizza can't review this right now, so it needs your approval");
    }
    AgentEntity agent = agents.findById(ctx.agentId()).orElse(null);
    if (agent == null) return decision.withRule(AutonomyRule.DENY, "Unknown agent");
    JizzaReviewer.Verdict verdict;
    try {
      verdict = jizza.review(agent.getUserId(), agent.getId(), ctx, decision);
    } catch (RuntimeException e) {
      log.warn("Jizza could not review {} for agent {}", ctx.toolRef(), agent.getId(), e);
      verdict = JizzaReviewer.Verdict.ask("Jizza couldn't review this");
    }
    if (audit != null) {
      audit.record(agent.getUserId(), ActorType.AGENT, null,
          verdict.approved() ? "guardrail.jizza_approved" : "guardrail.jizza_asked",
          ctx.toolRef(), Json.obj()
              .put("agentId", agent.getId().toString())
              .put("policy", decision.matchedPolicy())
              .put("instructionId", verdict.instructionId() == null ? null
                  : verdict.instructionId().toString())
              .put("explanation", verdict.explanation()));
    }
    if (verdict.approved() && verdict.instructionId() != null) {
      String why = "Jizza approved this because of your instruction: "
          + Objects.requireNonNullElse(verdict.instructionText(), verdict.explanation());
      return new Decision(AutonomyRule.ALLOW, why, decision.matchedPolicy(), decision.policyId(),
          decision.actionType(), decision.budgeted());
    }
    String reason = verdict.explanation() == null || verdict.explanation().isBlank()
        ? "None of your instructions clearly covers this, so it needs your approval"
        : verdict.explanation();
    return decision.withRule(AutonomyRule.ASK, reason);
  }

  private Optional<String> unmet(AutonomyPolicyEntity policy, GuardrailContext ctx) {
    Instant now = Instant.now();
    Optional<String> unmet = conditions.check(policy.getLimits(), ctx.args(), now);
    if (unmet.isPresent()) return unmet;
    if (ledger != null && GuardrailConditions.hasBudget(policy.getLimits())) {
      return ledger.wouldExceed(policy, ctx.args(), now);
    }
    return Optional.empty();
  }

  private Decision fromRule(AutonomyPolicyEntity policy, GuardrailContext ctx, ActionType type,
                            AgentEntity agent) {
    String label = describe(policy);
    boolean budgeted = !ctx.probe() && GuardrailConditions.hasBudget(policy.getLimits());
    String tool = ctx.toolRef();
    return switch (policy.getRule()) {
      case DENY -> new Decision(AutonomyRule.DENY,
          "Your guardrails never let " + agent.getName() + " run " + tool, label, policy.getId(),
          type, false);
      case ASK -> new Decision(AutonomyRule.ASK,
          "Your guardrails ask you before " + agent.getName() + " runs " + tool, label,
          policy.getId(), type, false);
      case JIZZA -> new Decision(AutonomyRule.JIZZA,
          "Your guardrails let Jizza decide whether " + agent.getName() + " may run " + tool,
          label, policy.getId(), type, budgeted);
      case ALLOW -> new Decision(AutonomyRule.ALLOW, "allowed by your guardrails", label,
          policy.getId(), type, budgeted);
    };
  }

  /** Applies the floor from an unmet condition, the limits on Jizza's say, and any lock. */
  private Decision finish(Decision decision, String fellThrough, AutonomyRule ceiling,
                          String ceilingLabel, AgentEntity agent, ActionType type,
                          AutonomyPolicyEntity policy) {
    Decision out = decision;
    // A call that missed a looser rule's conditions is never treated as anything looser than ask,
    // and when it ends up asking, the unmet condition is the reason the user is shown.
    if (fellThrough != null && out.rule().strictness() <= AutonomyRule.ASK.strictness()) {
      out = out.withRule(AutonomyRule.ASK, fellThrough);
    }
    if (out.needsJizza()) {
      if (agent.isOrchestrator()) {
        // Jizza never approves its own actions.
        out = out.withRule(AutonomyRule.ASK, "Jizza doesn't approve its own actions, so this "
            + "needs your approval");
      } else if ((type == ActionType.MONEY || type == ActionType.DELETE)
          && (policy == null || !GuardrailConditions.hasCondition(policy.getLimits())
              || !GuardrailConditions.hasBudget(policy.getLimits()))) {
        out = out.withRule(AutonomyRule.ASK, "Jizza can only decide on "
            + type.name().toLowerCase(Locale.ROOT) + " actions when the rule has a condition "
            + "and a budget, so this needs your approval");
      }
    }
    if (ceiling != null && ceiling.strictness() > out.rule().strictness()) {
      String reason = ceiling == AutonomyRule.DENY
          ? "A locked guardrail never allows this"
          : "A locked guardrail requires your approval for this";
      out = out.withRule(ceiling, reason).withMatched(ceilingLabel);
    }
    return out;
  }

  private boolean matches(AutonomyPolicyEntity policy, GuardrailContext ctx, ActionType type) {
    if (policy.getAgentId() != null && !policy.getAgentId().equals(ctx.agentId())) return false;
    if (policy.getWorkflowId() != null) {
      if (!policy.getWorkflowId().equals(ctx.workflowId())) return false;
      if (policy.getWorkflowVersion() != null
          && !policy.getWorkflowVersion().equals(ctx.workflowVersion())) {
        return false;
      }
      if (policy.getNodeId() != null && !policy.getNodeId().equals(ctx.nodeId())) return false;
    }
    return toolMatches(policy.getToolRef(), ctx.toolRef(), type);
  }

  static boolean toolMatches(String ref, String toolRef, ActionType type) {
    if (ref == null || ref.isBlank() || ANY.equals(ref)) return true;
    if (ANY_READ.equals(ref)) return !type.sideEffecting();
    if (ANY_SIDE_EFFECTING.equals(ref)) return type.sideEffecting();
    if (ref.startsWith("*:")) return ref.equalsIgnoreCase(type.token());
    if (ref.endsWith("*")) {
      return toolRef.toLowerCase(Locale.ROOT)
          .startsWith(ref.substring(0, ref.length() - 1).toLowerCase(Locale.ROOT));
    }
    return ref.equalsIgnoreCase(toolRef);
  }

  /** Step, then workflow, then agent, then account. */
  static int scopeTier(AutonomyPolicyEntity policy) {
    if (policy.getNodeId() != null) return 4;
    if (policy.getWorkflowId() != null) return 3;
    if (policy.getAgentId() != null) return 2;
    return 1;
  }

  static int specificity(String ref) {
    if (ref == null || ref.isBlank() || ANY.equals(ref)) return 10;
    if (ANY_READ.equals(ref) || ANY_SIDE_EFFECTING.equals(ref)) return 30;
    if (ref.startsWith("*:")) return 40;
    if (ref.endsWith("*")) return 60;
    return 100;
  }

  static String describe(AutonomyPolicyEntity policy) {
    String scope = policy.getWorkspaceId() != null ? "organization"
        : policy.getNodeId() != null ? "step"
        : policy.getWorkflowId() != null ? "workflow"
        : policy.getAgentId() != null ? "agent" : "global";
    return scope + ":" + (policy.getToolRef() == null ? ANY : policy.getToolRef());
  }
}
