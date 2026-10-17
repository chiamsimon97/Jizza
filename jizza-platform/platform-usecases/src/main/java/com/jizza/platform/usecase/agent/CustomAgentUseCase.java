package com.jizza.platform.usecase.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.agent.Agent;
import com.jizza.engine.api.agent.AgentRequest;
import com.jizza.engine.api.agent.AgentResult;
import com.jizza.engine.api.agent.CapabilityRegistry;
import com.jizza.engine.api.agent.TeamTrail;
import com.jizza.engine.api.agent.TrialRun;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.AutonomyPolicyStore;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.autonomy.GuardrailContext;
import com.jizza.engine.api.connector.ConnectionStore;
import com.jizza.engine.api.connector.ToolCatalog;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.dto.AutonomyRuleDto;
import com.jizza.engine.api.dto.ConnectionDto;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.PermissionDeniedException;
import com.jizza.engine.api.error.StepUpRequiredException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.memory.Actor;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryFilter;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.api.orchestrator.AgentDependents;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.orchestrator.AgentRevision;
import com.jizza.engine.api.orchestrator.AgentStore;
import com.jizza.engine.api.orchestrator.CustomAgentDraft;
import com.jizza.engine.api.service.ServiceDefinitions;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.agent.AgentBlueprint.ToolRef;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agents the user makes themselves, and the teams they arrange them in.
 *
 * <p>Everything a custom agent may do is decided here and in the engine's own checks, never by what
 * its instructions say: which tools it holds, the guardrail rules seeded for it, the memory it can
 * read, and how far down a team may go. The orchestrator flag is never on offer, and a custom agent
 * starts from the strict "made by you" profile, no looser than the manager it reports to.
 *
 * <p>Step-up is decided here too, because whether a save needs it depends on what the save does:
 * granting tools, loosening a rule or widening memory access does, a draft with instructions only
 * does not. The caller passes whether the session has re-authenticated recently.
 */
@Service
public class CustomAgentUseCase {

  public static final int MAX_NAME = 60;
  public static final int MAX_DESCRIPTION = 300;
  public static final int MAX_INSTRUCTIONS = 4000;
  public static final int MAX_ACTIVE_CUSTOM = 25;
  public static final int MAX_TOOLS = 40;
  public static final int MAX_DEPTH = 4;
  public static final int MAX_REPORTS = 8;
  public static final int MAX_RESPONSIBILITIES = 8;
  public static final List<String> SHARED_DOMAINS = List.of("finance", "health", "professional");

  /** The profile an agent you make starts from: reads and drafts alone, asks for the rest. */
  public static final Map<ActionType, AutonomyRule> CREATED_PROFILE = profile();

  private static final SecureRandom RANDOM = new SecureRandom();

  /** What the form, a template or a proposal asks for. Null fields take their defaults. */
  public record Input(String name, String description, String instructions, String modelTier,
                      List<String> memoryDomains, JsonNode avatar, Double monthlyCostLimit,
                      UUID parentAgentId, List<String> responsibilities, String handsOffTo,
                      Boolean memoryFromManager, String status, List<ToolRef> tools,
                      Map<ActionType, AutonomyRule> modes, Set<ActionType> locks,
                      UUID originTemplateId, Integer originTemplateVersion) {

    public static Input from(AgentBlueprint b, UUID parentAgentId, String status) {
      return new Input(b.name(), b.description(), b.instructions(), b.modelTier(),
          b.memoryDomains(), b.avatar(), null, parentAgentId, b.responsibilities(),
          b.handsOffTo(), true, status, b.tools(), b.modes(), b.locks(), null, null);
    }

    Input withParent(UUID parent) {
      return new Input(name, description, instructions, modelTier, memoryDomains, avatar,
          monthlyCostLimit, parent, responsibilities, handsOffTo, memoryFromManager, status, tools,
          modes, locks, originTemplateId, originTemplateVersion);
    }

    Input withOrigin(UUID templateId, Integer version) {
      return new Input(name, description, instructions, modelTier, memoryDomains, avatar,
          monthlyCostLimit, parentAgentId, responsibilities, handsOffTo, memoryFromManager, status,
          tools, modes, locks, templateId, version);
    }
  }

  /** An edit. Only fields that are set change; tools and rules change through their own screens. */
  public record Patch(String name, String description, String instructions, String modelTier,
                      List<String> memoryDomains, JsonNode avatar, Double monthlyCostLimit,
                      boolean clearMonthlyCostLimit, List<String> responsibilities,
                      String handsOffTo, Boolean memoryFromManager) {}

  /**
   * How to archive an agent.
   *
   * @param reports   {@code move_up} hands its reports to its own manager; {@code pause} pauses
   *                  them where they are
   * @param reassignTo when set, the workflows it owns and its open tasks go to this agent
   */
  public record ArchiveOptions(String reports, UUID reassignTo) {}

  public record ArchiveResult(AgentDto agent, int reassigned, int reportsMoved, int reportsPaused,
                              List<AgentDependents.Dependent> dependents) {}

  /** One node of the org chart. */
  public record TeamNode(AgentDto agent, int depth, List<TeamNode> reports) {}

  /** A trial of the agent: its answer, who it handed work to, and what it would have done. */
  public record Trial(String answer, String error, List<String> toolsUsed,
                      List<TrialRun.Held> wouldHaveDone, List<TeamTrail.Step> team, int tokens) {}

  /** A tool the user could grant, with what kind of action it is. */
  public record ToolOption(CapabilityType type, UUID ref, String name, String description,
                           String source, ActionType actionType, UUID workspaceId) {}

  /** What an agent may do on its own, per kind of action, and what caps it. */
  public record ModeLine(ActionType actionType, AutonomyRule rule, AutonomyRule ceiling,
                         String ceilingFrom) {}

  private final AgentStore store;
  private final AgentDirectory directory;
  private final AgentDependents dependents;
  private final CapabilityRegistry capabilities;
  private final AutonomyGate gate;
  private final AutonomyPolicyStore policies;
  private final AuditLog audit;
  private final PermissionEvaluator permissions;
  private final ToolCatalog tools;
  private final ServiceDefinitions services;
  private final ConnectionStore connections;
  private final Agent baseAgent;
  private final CostAccountant costs;
  private final MemoryStore memory;

  public CustomAgentUseCase(AgentStore store, AgentDirectory directory,
                            AgentDependents dependents, CapabilityRegistry capabilities,
                            AutonomyGate gate, AutonomyPolicyStore policies, AuditLog audit,
                            PermissionEvaluator permissions, ToolCatalog tools,
                            ServiceDefinitions services, ConnectionStore connections,
                            Agent baseAgent, CostAccountant costs, MemoryStore memory) {
    this.store = store;
    this.directory = directory;
    this.dependents = dependents;
    this.capabilities = capabilities;
    this.gate = gate;
    this.policies = policies;
    this.audit = audit;
    this.permissions = permissions;
    this.tools = tools;
    this.services = services;
    this.connections = connections;
    this.baseAgent = baseAgent;
    this.costs = costs;
    this.memory = memory;
  }

  // ── Creating ─────────────────────────────────────────────────────────────────

  /** Creates the agent with its tools and rules in one go; a refusal leaves nothing behind. */
  @Transactional
  public AgentDto create(UUID userId, Input input, BooleanSupplier stepUpFresh) {
    return createInternal(userId, input, stepUpFresh, new HashMap<>());
  }

  /**
   * Adds a whole team under {@code parentAgentId} (Jizza when null): the blueprints' lead first,
   * then each report under the agent its {@code reportsTo} names. All of it, or none of it.
   */
  @Transactional
  public List<AgentDto> createTeam(UUID userId, List<AgentBlueprint> team, UUID parentAgentId,
                                   UUID originTemplateId, Integer originTemplateVersion,
                                   BooleanSupplier stepUpFresh) {
    if (team.isEmpty()) throw new ValidationException("A team needs at least one agent");
    if (team.size() > MAX_REPORTS * 2) {
      throw new ValidationException("A team template is limited to " + (MAX_REPORTS * 2)
          + " agents");
    }
    UUID lead = parentAgentId != null ? parentAgentId : directory.orchestrator(userId).id();
    Map<String, UUID> byRef = new HashMap<>();
    List<AgentDto> created = new ArrayList<>();
    List<AgentBlueprint> pending = new ArrayList<>(team);
    // Managers before their reports; whatever is left once nothing more resolves is malformed.
    while (!pending.isEmpty()) {
      boolean progressed = false;
      for (var it = pending.iterator(); it.hasNext(); ) {
        AgentBlueprint b = it.next();
        UUID parent = b.reportsTo() == null ? lead : byRef.get(b.reportsTo());
        if (parent == null) continue;
        Input input = Input.from(b, parent, "active")
            .withOrigin(originTemplateId, originTemplateVersion);
        AgentDto agent = createInternal(userId, input, stepUpFresh, byRef);
        if (b.ref() != null) byRef.put(b.ref(), agent.id());
        created.add(agent);
        it.remove();
        progressed = true;
      }
      if (!progressed) {
        throw new ValidationException("Some agents in this team report to someone who isn't in it: "
            + pending.stream().map(AgentBlueprint::name).toList());
      }
    }
    return created;
  }

  private AgentDto createInternal(UUID userId, Input input, BooleanSupplier stepUpFresh,
                                  Map<String, UUID> sameTeam) {
    String status = input.status() == null ? "active" : input.status();
    if (!status.equals("active") && !status.equals("draft")) {
      throw new ValidationException("A new agent is either active or a draft");
    }
    String name = validName(userId, input.name(), null);
    String description = validDescription(input.description(), status.equals("active"));
    String instructions = validInstructions(input.instructions());
    String tier = validTier(input.modelTier());
    List<String> domains = validDomains(input.memoryDomains());
    List<String> responsibilities = validResponsibilities(input.responsibilities());
    Double limit = validLimit(input.monthlyCostLimit());
    List<ToolRef> grants = input.tools() == null ? List.of() : input.tools();
    if (grants.size() > MAX_TOOLS) {
      throw new ValidationException("An agent can hold at most " + MAX_TOOLS + " tools");
    }

    AgentDto parent = input.parentAgentId() == null ? directory.orchestrator(userId)
        : owned(userId, input.parentAgentId());
    requireCanTakeReport(parent);
    if (status.equals("active") && activeCustomCount(userId) >= MAX_ACTIVE_CUSTOM) {
      throw new ValidationException("You can have up to " + MAX_ACTIVE_CUSTOM
          + " active agents of your own. Archive one to make room.");
    }

    // What it may do on its own: the safe profile, no looser than its manager, then what was
    // asked for, which may not pass a lock anywhere above it.
    Map<ActionType, AutonomyRule> start = startingProfile(parent);
    Map<ActionType, Ceiling> ceilings = ceilings(userId, chainFrom(parent));
    Map<ActionType, AutonomyRule> modes = new EnumMap<>(start);
    boolean looser = false;
    if (input.modes() != null) {
      for (var entry : input.modes().entrySet()) {
        ActionType type = entry.getKey();
        AutonomyRule rule = entry.getValue();
        Ceiling ceiling = ceilings.get(type);
        if (ceiling != null && rule.strictness() < ceiling.rule().strictness()) {
          throw new ValidationException(label(type) + " can't be " + ruleLabel(rule)
              + ": it is locked at " + ruleLabel(ceiling.rule()) + " on " + ceiling.from() + ".");
        }
        if (rule.strictness() < start.get(type).strictness()) looser = true;
        modes.put(type, rule);
      }
    }
    if ((looser || !grants.isEmpty() || !domains.isEmpty()) && !stepUpFresh.getAsBoolean()) {
      throw new StepUpRequiredException();
    }
    for (ToolRef tool : grants) requireConnectorAccess(userId, tool);

    String key = newKey(userId, name);
    AgentDto agent = store.createCustom(userId, key, new CustomAgentDraft(name, description,
        instructions, tier, domains, input.avatar(), limit, parent.id(), responsibilities,
        trim(input.handsOffTo()),
        input.memoryFromManager() == null || input.memoryFromManager(), status,
        input.originTemplateId(), input.originTemplateVersion()));
    store.recordRevision(agent.id(), userId);
    for (ToolRef tool : grants) capabilities.grant(agent.id(), tool.type(), tool.ref());
    Set<ActionType> locks = input.locks() == null ? Set.of() : input.locks();
    for (ActionType type : ActionType.values()) {
      policies.upsert(userId, new AutonomyRuleDto(null, userId, agent.id(), type.token(),
          modes.get(type), null, 10, null, null, null, locks.contains(type)));
    }
    audit.record(userId, ActorType.USER, null, "agent.created", agent.key(), Json.obj()
        .put("agentId", agent.id().toString()).put("name", name).put("status", status)
        .put("reportsTo", parent.id().toString()).put("tools", grants.size())
        .put("originTemplateId", input.originTemplateId() == null ? null
            : input.originTemplateId().toString()));
    return directory.byId(agent.id()).orElse(agent);
  }

  // ── Editing ──────────────────────────────────────────────────────────────────

  @Transactional
  public AgentDto update(UUID userId, UUID agentId, Patch patch, BooleanSupplier stepUpFresh) {
    AgentDto agent = owned(userId, agentId);
    requireCustom(agent, "Kai, Vita, Cato and Jizza keep the instructions the product gives them. "
        + "You can rename or pause them.");
    if ("archived".equals(agent.status())) {
      throw new ValidationException("Restore " + agent.name() + " before changing it");
    }
    String name = patch.name() == null ? agent.name() : validName(userId, patch.name(), agentId);
    String description = patch.description() == null ? agent.description()
        : validDescription(patch.description(), agent.active());
    String instructions = patch.instructions() == null ? agent.systemPrompt()
        : validInstructions(patch.instructions());
    String tier = patch.modelTier() == null ? agent.modelTier() : validTier(patch.modelTier());
    List<String> domains = patch.memoryDomains() == null ? agent.memoryDomains()
        : validDomains(patch.memoryDomains());
    if (!new HashSet<>(agent.memoryDomains()).containsAll(domains)
        && !stepUpFresh.getAsBoolean()) {
      throw new StepUpRequiredException();
    }
    Double limit = patch.clearMonthlyCostLimit() ? null
        : patch.monthlyCostLimit() == null ? agent.monthlyCostLimit()
        : validLimit(patch.monthlyCostLimit());

    AgentDto updated = store.updateCustom(agentId, new CustomAgentDraft(name, description,
        instructions, tier, domains, patch.avatar() == null ? agent.avatar() : patch.avatar(),
        limit, agent.parentAgentId(),
        patch.responsibilities() == null ? agent.responsibilities()
            : validResponsibilities(patch.responsibilities()),
        patch.handsOffTo() == null ? agent.handsOffTo() : trim(patch.handsOffTo()),
        patch.memoryFromManager() == null ? agent.memoryFromManager() : patch.memoryFromManager(),
        agent.status(), agent.originTemplateId(), agent.originTemplateVersion()));

    boolean behaviourChanged = !Objects.equals(name, agent.name())
        || !Objects.equals(description, agent.description())
        || !Objects.equals(instructions, agent.systemPrompt())
        || !Objects.equals(tier, agent.modelTier());
    Integer revision = behaviourChanged ? store.recordRevision(agentId, userId).revision() : null;
    audit.record(userId, ActorType.USER, null, "agent.updated", agent.key(), Json.obj()
        .put("agentId", agentId.toString()).put("revision", revision)
        .put("memoryDomains", String.join(",", domains)));
    return updated;
  }

  @Transactional(readOnly = true)
  public List<AgentRevision> revisions(UUID userId, UUID agentId) {
    owned(userId, agentId);
    return store.revisions(agentId);
  }

  /** Puts an earlier revision's name, use case, instructions and model back, as a new revision. */
  @Transactional
  public AgentDto restoreRevision(UUID userId, UUID agentId, int revision) {
    owned(userId, agentId);
    AgentRevision old = store.revisions(agentId).stream()
        .filter(r -> r.revision() == revision).findFirst()
        .orElseThrow(() -> new NotFoundException("Revision", revision));
    return update(userId, agentId, new Patch(old.name(), old.description(), old.systemPrompt(),
        old.modelTier(), null, null, null, false, null, null, null), () -> true);
  }

  /** Publishes a draft: it joins its manager's team. */
  @Transactional
  public AgentDto activate(UUID userId, UUID agentId) {
    AgentDto agent = owned(userId, agentId);
    requireCustom(agent, "This agent is always active.");
    if (!"draft".equals(agent.status())) return agent;
    validDescription(agent.description(), true);
    if (activeCustomCount(userId) >= MAX_ACTIVE_CUSTOM) {
      throw new ValidationException("You can have up to " + MAX_ACTIVE_CUSTOM
          + " active agents of your own. Archive one to make room.");
    }
    AgentDto active = store.setStatus(agentId, "active");
    audit.record(userId, ActorType.USER, null, "agent.activated", agent.key(), null);
    return active;
  }

  // ── Archiving and deleting ───────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<AgentDependents.Dependent> dependents(UUID userId, UUID agentId) {
    owned(userId, agentId);
    return dependents.of(userId, agentId);
  }

  @Transactional
  public ArchiveResult archive(UUID userId, UUID agentId, ArchiveOptions options) {
    AgentDto agent = owned(userId, agentId);
    requireCustom(agent, agent.name() + " is part of every account and can't be archived. "
        + "You can pause it instead.");
    if ("archived".equals(agent.status())) {
      return new ArchiveResult(agent, 0, 0, 0, dependents.of(userId, agentId));
    }

    int reassigned = 0;
    if (options != null && options.reassignTo() != null) {
      AgentDto to = owned(userId, options.reassignTo());
      if (to.id().equals(agentId) || !to.active() || !to.enabled()) {
        throw new ValidationException("Hand its work to an active agent other than itself");
      }
      reassigned = dependents.reassign(userId, agentId, to.id());
    }

    String reportsChoice = options == null || options.reports() == null ? "move_up"
        : options.reports();
    int moved = 0;
    int paused = 0;
    for (AgentDto report : directory.reportsOf(agentId)) {
      if (reportsChoice.equals("pause")) {
        if (report.enabled()) {
          store.setEnabled(report.id(), false);
          paused++;
        }
      } else {
        store.setParent(report.id(), agent.parentAgentId());
        moved++;
      }
    }

    AgentDto archived = store.setStatus(agentId, "archived");
    audit.record(userId, ActorType.USER, null, "agent.archived", agent.key(), Json.obj()
        .put("reassigned", reassigned).put("reportsMoved", moved).put("reportsPaused", paused));
    return new ArchiveResult(archived, reassigned, moved, paused, dependents.of(userId, agentId));
  }

  @Transactional
  public AgentDto restore(UUID userId, UUID agentId) {
    AgentDto agent = owned(userId, agentId);
    if (!"archived".equals(agent.status())) return agent;
    // Its name may have been taken while it was away.
    validName(userId, agent.name(), agentId);
    if (activeCustomCount(userId) >= MAX_ACTIVE_CUSTOM) {
      throw new ValidationException("You can have up to " + MAX_ACTIVE_CUSTOM
          + " active agents of your own. Archive one to make room.");
    }
    // Its manager may have been archived in the meantime; then it goes back to Jizza.
    if (agent.parentAgentId() == null || directory.byId(agent.parentAgentId())
        .map(p -> "archived".equals(p.status())).orElse(true)) {
      store.setParent(agentId, directory.orchestrator(userId).id());
    }
    AgentDto restored = store.setStatus(agentId, "active");
    audit.record(userId, ActorType.USER, null, "agent.restored", agent.key(), null);
    return restored;
  }

  /** For good: only an archived agent nothing points at. Its private memory goes with it. */
  @Transactional
  public void delete(UUID userId, UUID agentId) {
    AgentDto agent = owned(userId, agentId);
    requireCustom(agent, agent.name() + " is part of every account and can't be deleted.");
    if (!"archived".equals(agent.status())) {
      throw new ValidationException("Archive " + agent.name() + " before deleting it for good");
    }
    if (!dependents.of(userId, agentId).isEmpty() || !directory.reportsOf(agentId).isEmpty()) {
      throw new ValidationException(agent.name() + " is still used by workflows, tasks or other "
          + "agents. Reassign them first.");
    }
    Actor actor = Actor.user(userId);
    for (Memory fact : memory.list(userId, MemoryFilter.all(10_000))) {
      if (!fact.domains().contains(agent.domain())) continue;
      if (fact.domains().size() == 1) memory.forget(userId, fact.id(), actor);
      else memory.unshare(userId, fact.id(), agent.domain(), actor);
    }
    store.delete(agentId);
    audit.record(userId, ActorType.USER, null, "agent.deleted", agent.key(),
        Json.obj().put("name", agent.name()));
  }

  // ── Teams ────────────────────────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public TeamNode tree(UUID userId) {
    AgentDto jizza = directory.orchestrator(userId);
    return node(jizza, 0, new HashSet<>());
  }

  private TeamNode node(AgentDto agent, int depth, Set<UUID> seen) {
    seen.add(agent.id());
    List<TeamNode> children = new ArrayList<>();
    for (AgentDto report : directory.reportsOf(agent.id())) {
      if ("archived".equals(report.status()) || seen.contains(report.id())) continue;
      children.add(node(report, depth + 1, seen));
    }
    return new TeamNode(agent, depth, children);
  }

  /**
   * Moves an agent, with everyone below it, under another manager. Moving under a stricter manager
   * tightens it at once; moving somewhere whose locks are looser needs a fresh re-authentication.
   */
  @Transactional
  public AgentDto setManager(UUID userId, UUID agentId, UUID managerId,
                             BooleanSupplier stepUpFresh) {
    AgentDto agent = owned(userId, agentId);
    if (agent.orchestrator()) throw new ValidationException("Jizza doesn't report to anyone");
    if (!agent.custom()) {
      throw new ValidationException(agent.name() + " always reports to Jizza");
    }
    AgentDto manager = owned(userId, managerId);
    if (managerId.equals(agent.parentAgentId())) return agent;
    if (managerId.equals(agentId) || descendants(agentId).contains(managerId)) {
      throw new ValidationException(agent.name() + " can't report to someone in its own team");
    }
    requireCanTakeReport(manager);
    int newDepth = depthOf(manager) + 1;
    if (newDepth + subtreeHeight(agentId) > MAX_DEPTH) {
      throw new ValidationException("Teams go at most " + MAX_DEPTH + " levels below Jizza; "
          + "moving " + agent.name() + " there would make it deeper.");
    }

    Map<ActionType, Ceiling> before = ceilings(userId, directory.managersOf(agentId));
    Map<ActionType, Ceiling> after = ceilings(userId, chainFrom(manager));
    boolean loosens = false;
    for (ActionType type : ActionType.values()) {
      Ceiling was = before.get(type);
      Ceiling now = after.get(type);
      if (was != null && (now == null || now.rule().strictness() < was.rule().strictness())) {
        loosens = true;
      }
    }
    if (loosens && !stepUpFresh.getAsBoolean()) throw new StepUpRequiredException();

    AgentDto moved = store.setParent(agentId, managerId);
    audit.record(userId, ActorType.USER, null, "agent.moved", agent.key(), Json.obj()
        .put("from", String.valueOf(agent.parentAgentId())).put("to", managerId.toString())
        .put("loosens", loosens));
    return moved;
  }

  /** What each kind of action would do for this agent now, and what lock caps it. */
  @Transactional(readOnly = true)
  public List<ModeLine> modes(UUID userId, UUID agentId) {
    AgentDto agent = owned(userId, agentId);
    List<AgentDto> chain = new ArrayList<>();
    chain.add(agent);
    chain.addAll(directory.managersOf(agentId));
    Map<ActionType, Ceiling> ceilings = ceilings(userId, chain);
    List<ModeLine> out = new ArrayList<>();
    for (ActionType type : ActionType.values()) {
      Decision d = TrialRun.run(!agent.enabled(), () -> modeOf(agent.id(), type));
      Ceiling c = ceilings.get(type);
      out.add(new ModeLine(type, d.rule(), c == null ? null : c.rule(),
          c == null ? null : c.from()));
    }
    return out;
  }

  // ── Trying it ────────────────────────────────────────────────────────────────

  /**
   * Runs the agent on a sample request for real, except that nothing which changes the outside
   * world is carried out, here or anywhere in its team; each such call comes back as what the
   * guardrails would have done.
   */
  public Trial tryOut(UUID userId, UUID agentId, String message) {
    AgentDto agent = owned(userId, agentId);
    if (agent.orchestrator()) {
      throw new ValidationException("Talk to Jizza in chat; trials are for the agents behind it");
    }
    if ("archived".equals(agent.status())) {
      throw new ValidationException("Restore " + agent.name() + " before trying it");
    }
    if (message == null || message.isBlank()) throw new ValidationException("Say what to try");
    if (!costs.withinBudget(userId)) {
      throw new ValidationException("Your spending limit for this period has been reached");
    }
    ModelTier tier = "deep".equals(agent.modelTier()) ? ModelTier.DEEP : ModelTier.STANDARD;
    TrialRun.Result<TeamTrail.Recorded<AgentResult>> run;
    try {
      run = TrialRun.record(() -> TeamTrail.record(() -> baseAgent.run(new AgentRequest(userId,
          agentId, message.trim(), null, List.of(), tier, false, null, true))));
    } catch (RuntimeException e) {
      return new Trial(null, e.getMessage(), List.of(), List.of(), List.of(), 0);
    }
    AgentResult result = run.value().value();
    audit.record(userId, ActorType.USER, null, "agent.tried", agent.key(), Json.obj()
        .put("held", run.held().size()).put("handOffs", run.value().steps().size()));
    return new Trial(result.text(), result.error(),
        result.toolCallsMade().stream().map(call -> call.name()).distinct().toList(),
        run.held(), run.value().steps(), result.inputTokens() + result.outputTokens());
  }

  /** What the agent has spent this calendar month, in the account's currency units. */
  @Transactional(readOnly = true)
  public double spentThisMonth(UUID userId, UUID agentId) {
    AgentDto agent = owned(userId, agentId);
    Instant start = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay()
        .toInstant(ZoneOffset.UTC);
    return costs.spendSince(userId, "agent:" + agent.key(), start);
  }

  // ── Tools ────────────────────────────────────────────────────────────────────

  /** Every tool the user may hand an agent, from their own and their organizations' connectors. */
  @Transactional(readOnly = true)
  public List<ToolOption> toolOptions(UUID userId, List<UUID> workspaceIds) {
    UUID probeAgent = directory.orchestrator(userId).id();
    List<ToolOption> out = new ArrayList<>();
    for (UUID workspaceId : workspaceIds) {
      if (!permissions.isAllowed(userId, workspaceId, Permissions.CONNECTOR_USE)) continue;
      for (ConnectionDto connection : connections.list(workspaceId)) {
        for (GeneratedToolDto tool : tools.list(connection.id())) {
          if (!tool.enabled()) continue;
          out.add(new ToolOption(CapabilityType.GENERATED_TOOL, tool.id(), tool.name(),
              tool.description(), connection.serviceName(),
              gate.actionTypeOf(probeAgent, tool.name()), workspaceId));
        }
      }
      for (var service : services.list(workspaceId)) {
        for (var action : services.actions(service.id())) {
          if (!action.enabled()) continue;
          String toolName = action.toolName() == null ? action.name() : action.toolName();
          out.add(new ToolOption(CapabilityType.SERVICE_ACTION, action.id(),
              action.label() == null ? action.name() : action.label(), action.description(),
              service.name(), gate.actionTypeOf(probeAgent, toolName), workspaceId));
        }
      }
    }
    return out;
  }

  /** Tools whose name or description share words with what the agent is for. */
  public static List<ToolOption> suggest(List<ToolOption> options, String useCase,
                                         List<String> hints, int limit) {
    Set<String> words = new HashSet<>();
    for (String text : concat(useCase, hints)) {
      for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
        if (w.length() > 3 && !STOP_WORDS.contains(w)) words.add(stem(w));
      }
    }
    Map<ToolOption, Integer> scores = new LinkedHashMap<>();
    for (ToolOption option : options) {
      String text = (option.name() + " " + option.source() + " "
          + (option.description() == null ? "" : option.description())).toLowerCase(Locale.ROOT);
      int score = 0;
      for (String w : text.split("[^a-z0-9]+")) if (words.contains(stem(w))) score++;
      if (score > 0) scores.put(option, score);
    }
    return scores.entrySet().stream()
        .sorted(Map.Entry.<ToolOption, Integer>comparingByValue().reversed())
        .limit(limit).map(Map.Entry::getKey).toList();
  }

  private static final Set<String> STOP_WORDS = Set.of("that", "this", "with", "from", "your",
      "when", "what", "they", "them", "have", "will", "about", "into", "each", "their", "agent");

  private static String stem(String w) {
    if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
    if (w.endsWith("s") && w.length() > 4) return w.substring(0, w.length() - 1);
    return w;
  }

  private static List<String> concat(String first, List<String> rest) {
    List<String> out = new ArrayList<>();
    if (first != null) out.add(first);
    if (rest != null) out.addAll(rest);
    return out;
  }

  /**
   * Agents are personal, but may be handed a tool from an organization's connector. That is only
   * allowed while the agent's owner may use that organization's connectors.
   */
  public void requireConnectorAccess(UUID userId, ToolRef tool) {
    UUID connectionId = switch (tool.type()) {
      case GENERATED_TOOL -> tools.find(tool.ref()).map(GeneratedToolDto::connectionId)
          .orElseThrow(() -> new NotFoundException("Tool", tool.ref()));
      case SERVICE_ACTION -> services.action(tool.ref()).map(a -> a.serviceId())
          .orElseThrow(() -> new NotFoundException("Action", tool.ref()));
      default -> null;
    };
    if (connectionId == null) return;
    // A service's actions are served through the connection that shares its id.
    UUID workspaceId = connections.find(connectionId).map(ConnectionDto::workspaceId).orElse(null);
    if (workspaceId == null
        || !permissions.isAllowed(userId, workspaceId, Permissions.CONNECTOR_USE)) {
      throw new PermissionDeniedException(Permissions.CONNECTOR_USE, "connectors");
    }
  }

  // ── Rules ────────────────────────────────────────────────────────────────────

  /** A lock and where it comes from, as the user would name it. */
  record Ceiling(AutonomyRule rule, String from) {}

  /**
   * The locks that cap anyone working for this chain of managers: the account-wide locks, and the
   * locks on each manager other than Jizza (Jizza's own locks stay its own).
   */
  Map<ActionType, Ceiling> ceilings(UUID userId, List<AgentDto> chain) {
    Map<UUID, AgentDto> managers = new HashMap<>();
    for (AgentDto a : chain) if (!a.orchestrator()) managers.put(a.id(), a);
    Map<ActionType, Ceiling> out = new EnumMap<>(ActionType.class);
    for (AutonomyRuleDto rule : policies.list(userId)) {
      if (!rule.locked() || rule.workspaceId() != null || rule.workflowId() != null) continue;
      String from;
      if (rule.agentId() == null) from = "your whole account";
      else if (managers.containsKey(rule.agentId())) from = managers.get(rule.agentId()).name();
      else continue;
      for (ActionType type : ActionType.values()) {
        if (!covers(rule.toolRef(), type)) continue;
        Ceiling current = out.get(type);
        if (current == null || rule.rule().strictness() > current.rule().strictness()) {
          out.put(type, new Ceiling(rule.rule(), from));
        }
      }
    }
    return out;
  }

  private static boolean covers(String toolRef, ActionType type) {
    if (toolRef == null || toolRef.equals("*")) return true;
    if (toolRef.equals(type.token())) return true;
    if (toolRef.equals(AutonomyGate.ANY_SIDE_EFFECTING)) return type.sideEffecting();
    if (toolRef.equals(AutonomyGate.ANY_READ)) return !type.sideEffecting();
    return false;
  }

  /** The created profile, made no looser than what the manager may do itself. */
  Map<ActionType, AutonomyRule> startingProfile(AgentDto manager) {
    Map<ActionType, AutonomyRule> out = new EnumMap<>(CREATED_PROFILE);
    if (manager.orchestrator()) return out;
    for (ActionType type : ActionType.values()) {
      AutonomyRule theirs = TrialRun.run(!manager.enabled(), () -> modeOf(manager.id(), type))
          .rule();
      if (theirs.strictness() > out.get(type).strictness()) out.put(type, theirs);
    }
    return out;
  }

  private Decision modeOf(UUID agentId, ActionType type) {
    return gate.evaluate(new GuardrailContext(agentId, type.token(), null, null, null, null, true,
        type));
  }

  private static Map<ActionType, AutonomyRule> profile() {
    Map<ActionType, AutonomyRule> p = new EnumMap<>(ActionType.class);
    for (ActionType type : ActionType.values()) {
      p.put(type, switch (type) {
        case READ, DRAFT -> AutonomyRule.ALLOW;
        case MONEY -> AutonomyRule.DENY;
        default -> AutonomyRule.ASK;
      });
    }
    return Map.copyOf(p);
  }

  // ── Checks ───────────────────────────────────────────────────────────────────

  public AgentDto owned(UUID userId, UUID agentId) {
    return directory.byId(agentId)
        .filter(agent -> agent.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Agent", agentId));
  }

  private static void requireCustom(AgentDto agent, String message) {
    if (!agent.custom()) throw new ValidationException(message);
  }

  private void requireCanTakeReport(AgentDto manager) {
    if ("archived".equals(manager.status())) {
      throw new ValidationException(manager.name() + " is archived and can't have reports");
    }
    if (depthOf(manager) + 1 > MAX_DEPTH) {
      throw new ValidationException("Teams go at most " + MAX_DEPTH + " levels below Jizza, so "
          + manager.name() + " can't have reports.");
    }
    long reports = directory.reportsOf(manager.id()).stream()
        .filter(r -> !"archived".equals(r.status())).count();
    // Jizza's own roster is wider: built-ins plus the leads of every team.
    int cap = manager.orchestrator() ? MAX_ACTIVE_CUSTOM + 3 : MAX_REPORTS;
    if (reports >= cap) {
      throw new ValidationException(manager.name() + " already has " + reports
          + " reports, the most an agent can have");
    }
  }

  private int depthOf(AgentDto agent) {
    return agent.orchestrator() ? 0 : directory.managersOf(agent.id()).size();
  }

  private List<AgentDto> chainFrom(AgentDto manager) {
    List<AgentDto> chain = new ArrayList<>();
    chain.add(manager);
    chain.addAll(directory.managersOf(manager.id()));
    return chain;
  }

  private Set<UUID> descendants(UUID agentId) {
    Set<UUID> out = new HashSet<>();
    List<UUID> frontier = new ArrayList<>(List.of(agentId));
    while (!frontier.isEmpty()) {
      UUID next = frontier.remove(frontier.size() - 1);
      for (AgentDto report : directory.reportsOf(next)) {
        if (out.add(report.id())) frontier.add(report.id());
      }
    }
    return out;
  }

  /** Levels below the agent: 0 when it has no reports. */
  private int subtreeHeight(UUID agentId) {
    int height = 0;
    for (AgentDto report : directory.reportsOf(agentId)) {
      if ("archived".equals(report.status())) continue;
      height = Math.max(height, 1 + subtreeHeight(report.id()));
    }
    return height;
  }

  private long activeCustomCount(UUID userId) {
    return directory.list(userId).stream()
        .filter(a -> a.custom() && "active".equals(a.status())).count();
  }

  private String validName(UUID userId, String raw, UUID self) {
    String name = trim(raw);
    if (name == null) throw new ValidationException("An agent needs a name");
    if (name.length() > MAX_NAME) {
      throw new ValidationException("Agent names are limited to " + MAX_NAME + " characters");
    }
    if (name.equalsIgnoreCase("jizza")) {
      throw new ValidationException("Only your assistant is called Jizza");
    }
    boolean taken = directory.list(userId).stream()
        .filter(a -> !a.id().equals(self) && !"archived".equals(a.status()))
        .anyMatch(a -> a.name().equalsIgnoreCase(name));
    if (taken) throw new ValidationException("You already have an agent called " + name);
    return name;
  }

  private static String validDescription(String raw, boolean required) {
    String description = trim(raw);
    if (description == null && required) {
      throw new ValidationException("Say when to use this agent, so its manager knows what to "
          + "hand it");
    }
    if (description != null && description.length() > MAX_DESCRIPTION) {
      throw new ValidationException("\"When to use me\" is limited to " + MAX_DESCRIPTION
          + " characters");
    }
    return description;
  }

  private static String validInstructions(String raw) {
    String instructions = trim(raw);
    if (instructions == null) throw new ValidationException("Tell the agent how to work");
    if (instructions.length() > MAX_INSTRUCTIONS) {
      throw new ValidationException("Instructions are limited to " + MAX_INSTRUCTIONS
          + " characters");
    }
    return instructions;
  }

  private static String validTier(String raw) {
    String tier = raw == null ? "standard" : raw.trim().toLowerCase(Locale.ROOT);
    if (!tier.equals("standard") && !tier.equals("deep")) {
      throw new ValidationException("The model is either standard or deep");
    }
    return tier;
  }

  private static List<String> validDomains(List<String> raw) {
    if (raw == null) return List.of();
    List<String> out = new ArrayList<>();
    for (String d : raw) {
      String domain = d == null ? "" : d.trim().toLowerCase(Locale.ROOT);
      if (!SHARED_DOMAINS.contains(domain)) {
        throw new ValidationException("An agent can be given finance, health or professional "
            + "memory, not " + d);
      }
      if (!out.contains(domain)) out.add(domain);
    }
    return out;
  }

  private static List<String> validResponsibilities(List<String> raw) {
    if (raw == null) return List.of();
    List<String> out = raw.stream().map(CustomAgentUseCase::trim).filter(Objects::nonNull)
        .toList();
    if (out.size() > MAX_RESPONSIBILITIES) {
      throw new ValidationException("List at most " + MAX_RESPONSIBILITIES + " responsibilities");
    }
    for (String r : out) {
      if (r.length() > 120) throw new ValidationException("Keep each responsibility short");
    }
    return out;
  }

  private static Double validLimit(Double limit) {
    if (limit == null) return null;
    if (limit < 0 || limit.isNaN()) throw new ValidationException("A spending limit can't be negative");
    return limit;
  }

  private String newKey(UUID userId, String name) {
    String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
        .replaceAll("^_+|_+$", "");
    if (slug.isEmpty()) slug = "agent";
    if (slug.length() > 20) slug = slug.substring(0, 20).replaceAll("_+$", "");
    for (int attempt = 0; attempt < 10; attempt++) {
      String key = "c_" + slug + "_" + String.format("%04x", RANDOM.nextInt(0x10000));
      if (directory.byKey(userId, key).isEmpty()) return key;
    }
    throw new IllegalStateException("Could not find a free agent key");
  }

  private static String trim(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  static String label(ActionType type) {
    String n = type.name().toLowerCase(Locale.ROOT);
    return Character.toUpperCase(n.charAt(0)) + n.substring(1);
  }

  static String ruleLabel(AutonomyRule rule) {
    return switch (rule) {
      case ALLOW -> "Auto";
      case ASK -> "Ask me";
      case JIZZA -> "Let Jizza decide";
      case DENY -> "Never";
    };
  }
}
