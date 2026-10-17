package com.jizza.platform.usecase.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.agent.CapabilityRegistry;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.autonomy.AutonomyPolicyStore;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.dto.AutonomyRuleDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.PermissionDeniedException;
import com.jizza.engine.api.error.StepUpRequiredException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.orchestrator.AgentStore;
import com.jizza.engine.api.orchestrator.AgentTemplateStore;
import com.jizza.engine.api.orchestrator.AgentTemplateStore.Template;
import com.jizza.engine.api.orchestrator.CustomAgentDraft;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.platform.usecase.agent.AgentBlueprint.ToolRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting points for new agents: the product's own gallery of agents and teams, and the templates
 * an organization publishes for its members.
 *
 * <p>An organization shares definitions, never live agents. A member who adds one gets a personal
 * copy that remembers where it came from; when the template changes they choose whether to take
 * the update, and an update only ever makes the copy's rules stricter on its own.
 */
@Service
public class AgentTemplateUseCase {

  /** A starting point in the product's own gallery. */
  public record GalleryItem(String id, String kind, String name, String description,
                            List<AgentBlueprint> agents) {}

  /** An organization template as a member sees it. */
  public record OrgTemplate(UUID id, UUID workspaceId, String workspaceName, String name,
                            String description, String status, int version, String kind,
                            List<AgentBlueprint> agents) {}

  /** A copy of an organization template that is behind the template. */
  public record UpdateAvailable(UUID agentId, String agentName, UUID templateId,
                                String templateName, int from, int to) {}

  private final AgentTemplateStore templates;
  private final CustomAgentUseCase custom;
  private final AgentDirectory directory;
  private final AgentStore store;
  private final WorkspaceDirectory workspaces;
  private final PermissionEvaluator permissions;
  private final AutonomyPolicyStore policies;
  private final CapabilityRegistry capabilities;
  private final AuditLog audit;

  public AgentTemplateUseCase(AgentTemplateStore templates, CustomAgentUseCase custom,
                              AgentDirectory directory, AgentStore store,
                              WorkspaceDirectory workspaces, PermissionEvaluator permissions,
                              AutonomyPolicyStore policies, CapabilityRegistry capabilities,
                              AuditLog audit) {
    this.templates = templates;
    this.custom = custom;
    this.directory = directory;
    this.store = store;
    this.workspaces = workspaces;
    this.permissions = permissions;
    this.policies = policies;
    this.capabilities = capabilities;
    this.audit = audit;
  }

  // ── The product's gallery ────────────────────────────────────────────────────

  public List<GalleryItem> gallery() {
    return AgentGallery.ITEMS;
  }

  public GalleryItem galleryItem(String id) {
    return AgentGallery.ITEMS.stream().filter(item -> item.id().equals(id)).findFirst()
        .orElseThrow(() -> new NotFoundException("Template", id));
  }

  /** Adds a gallery team (or single agent) under a manager, as it stands in the gallery. */
  @Transactional
  public List<AgentDto> installGallery(UUID userId, String id, UUID parentAgentId,
                                       BooleanSupplier stepUpFresh) {
    return custom.createTeam(userId, galleryItem(id).agents(), parentAgentId, null, null,
        stepUpFresh);
  }

  // ── An organization's templates, for its admins ──────────────────────────────

  @Transactional(readOnly = true)
  public List<OrgTemplate> list(UUID workspaceId) {
    return templates.list(workspaceId).stream().map(this::view).toList();
  }

  @Transactional
  public OrgTemplate create(UUID userId, UUID workspaceId, String name, String description,
                            JsonNode definition) {
    List<AgentBlueprint> agents = validDefinition(userId, workspaceId, definition);
    Template saved = templates.create(workspaceId, userId, validName(name),
        trim(description), AgentBlueprint.teamJson(agents));
    audit.record(userId, ActorType.USER, null, "agent_template.created", saved.name(),
        Json.obj().put("templateId", saved.id().toString())
            .put("workspaceId", workspaceId.toString()));
    return view(saved);
  }

  @Transactional
  public OrgTemplate update(UUID userId, UUID workspaceId, UUID templateId, String name,
                            String description, JsonNode definition) {
    Template template = inWorkspace(workspaceId, templateId);
    List<AgentBlueprint> agents = definition == null
        ? AgentBlueprint.listFrom(template.definition())
        : validDefinition(userId, workspaceId, definition);
    Template saved = templates.update(templateId, name == null ? template.name() : validName(name),
        description == null ? template.description() : trim(description),
        AgentBlueprint.teamJson(agents));
    audit.record(userId, ActorType.USER, null, "agent_template.updated", saved.name(),
        Json.obj().put("templateId", templateId.toString()).put("version", saved.version()));
    return view(saved);
  }

  @Transactional
  public OrgTemplate publish(UUID userId, UUID workspaceId, UUID templateId) {
    inWorkspace(workspaceId, templateId);
    Template saved = templates.setStatus(templateId, "published");
    audit.record(userId, ActorType.USER, null, "agent_template.published", saved.name(),
        Json.obj().put("templateId", templateId.toString()).put("version", saved.version()));
    return view(saved);
  }

  /** Stops new installs. Copies keep working unless {@code pauseCopies}, which pauses them all. */
  @Transactional
  public OrgTemplate unpublish(UUID userId, UUID workspaceId, UUID templateId,
                               boolean pauseCopies) {
    inWorkspace(workspaceId, templateId);
    Template saved = templates.setStatus(templateId, "unpublished");
    int paused = 0;
    if (pauseCopies) {
      for (AgentDto copy : directory.fromTemplate(templateId)) {
        if (copy.enabled()) {
          store.setEnabled(copy.id(), false);
          paused++;
        }
      }
    }
    audit.record(userId, ActorType.USER, null, "agent_template.unpublished", saved.name(),
        Json.obj().put("templateId", templateId.toString()).put("copiesPaused", paused));
    return view(saved);
  }

  @Transactional
  public void delete(UUID userId, UUID workspaceId, UUID templateId) {
    Template template = inWorkspace(workspaceId, templateId);
    if ("published".equals(template.status())) {
      throw new ValidationException("Unpublish the template before deleting it");
    }
    templates.delete(templateId);
    audit.record(userId, ActorType.USER, null, "agent_template.deleted", template.name(), null);
  }

  // ── An organization's templates, for its members ─────────────────────────────

  /** The published templates of every organization the user belongs to and may view. */
  @Transactional(readOnly = true)
  public List<OrgTemplate> available(UUID userId) {
    List<UUID> visible = workspaces.membershipsOf(userId).stream()
        .filter(Membership::active)
        .map(Membership::workspaceId)
        .filter(ws -> permissions.isAllowed(userId, ws, Permissions.AGENT_TEMPLATE_VIEW))
        .toList();
    if (visible.isEmpty()) return List.of();
    return templates.published(visible).stream().map(this::view).toList();
  }

  /**
   * Adds the template to the user's own team. Tools the member's role can't use are left out
   * rather than failing the install; the organization's locks apply to the copy as they would to
   * anyone using its connectors.
   */
  @Transactional
  public List<AgentDto> install(UUID userId, UUID templateId, UUID parentAgentId,
                                BooleanSupplier stepUpFresh) {
    Template template = templates.find(templateId)
        .filter(t -> "published".equals(t.status()))
        .orElseThrow(() -> new NotFoundException("Template", templateId));
    if (!permissions.isAllowed(userId, template.workspaceId(),
        Permissions.AGENT_TEMPLATE_INSTALL)) {
      throw new PermissionDeniedException(Permissions.AGENT_TEMPLATE_INSTALL, "agent templates");
    }
    List<AgentBlueprint> agents = new ArrayList<>();
    for (AgentBlueprint b : AgentBlueprint.listFrom(template.definition())) {
      agents.add(withTools(b, usable(userId, b.tools())));
    }
    List<AgentDto> created = custom.createTeam(userId, agents, parentAgentId, template.id(),
        template.version(), stepUpFresh);
    audit.record(userId, ActorType.USER, null, "agent_template.installed", template.name(),
        Json.obj().put("templateId", templateId.toString()).put("version", template.version())
            .put("agents", created.size()));
    return created;
  }

  @Transactional(readOnly = true)
  public List<UpdateAvailable> updates(UUID userId) {
    List<UpdateAvailable> out = new ArrayList<>();
    for (AgentDto agent : directory.list(userId)) {
      if (agent.originTemplateId() == null || "archived".equals(agent.status())) continue;
      templates.find(agent.originTemplateId())
          .filter(t -> "published".equals(t.status()))
          .filter(t -> agent.originTemplateVersion() == null
              || t.version() > agent.originTemplateVersion())
          .ifPresent(t -> out.add(new UpdateAvailable(agent.id(), agent.name(), t.id(), t.name(),
              agent.originTemplateVersion() == null ? 0 : agent.originTemplateVersion(),
              t.version())));
    }
    return out;
  }

  /**
   * Takes the template's latest version: its use case, instructions and model, any tools it adds
   * that the member can use, and any rule that is now stricter. A rule the template loosened stays
   * as the member has it.
   */
  @Transactional
  public AgentDto applyUpdate(UUID userId, UUID agentId, BooleanSupplier stepUpFresh) {
    AgentDto agent = custom.owned(userId, agentId);
    if (agent.originTemplateId() == null) {
      throw new ValidationException(agent.name() + " wasn't made from an organization template");
    }
    Template template = templates.find(agent.originTemplateId())
        .orElseThrow(() -> new NotFoundException("Template", agent.originTemplateId()));
    List<AgentBlueprint> agents = AgentBlueprint.listFrom(template.definition());
    // A team copy is matched to its blueprint by name; a single agent is the only blueprint.
    AgentBlueprint b = agents.size() == 1 ? agents.get(0) : agents.stream()
        .filter(x -> x.name() != null && x.name().equalsIgnoreCase(agent.name())).findFirst()
        .orElse(agents.isEmpty() ? null : agents.get(0));
    if (b == null) throw new ValidationException("The template no longer describes an agent");

    List<ToolRef> newTools = usable(userId, b.tools()).stream()
        .filter(tool -> !holds(agent.id(), tool)).toList();
    if (!newTools.isEmpty() && !stepUpFresh.getAsBoolean()) throw new StepUpRequiredException();

    store.updateCustom(agentId, new CustomAgentDraft(agent.name(),
        b.description() == null ? agent.description() : b.description(),
        b.instructions() == null ? agent.systemPrompt() : b.instructions(),
        b.modelTier() == null ? agent.modelTier() : b.modelTier(), agent.memoryDomains(),
        agent.avatar(), agent.monthlyCostLimit(), agent.parentAgentId(),
        b.responsibilities().isEmpty() ? agent.responsibilities() : b.responsibilities(),
        b.handsOffTo() == null ? agent.handsOffTo() : b.handsOffTo(), agent.memoryFromManager(),
        agent.status(), template.id(), template.version()));
    store.recordRevision(agentId, userId);
    for (ToolRef tool : newTools) capabilities.grant(agentId, tool.type(), tool.ref());

    int tightened = 0;
    List<AutonomyRuleDto> rules = policies.list(userId);
    for (Map.Entry<ActionType, AutonomyRule> mode : b.modes().entrySet()) {
      AutonomyRuleDto current = rules.stream()
          .filter(r -> agentId.equals(r.agentId()) && r.workflowId() == null
              && mode.getKey().token().equals(r.toolRef()))
          .findFirst().orElse(null);
      boolean lock = b.locks().contains(mode.getKey());
      if (current == null || mode.getValue().strictness() > current.rule().strictness()
          || (lock && !current.locked())) {
        AutonomyRule rule = current == null || mode.getValue().strictness()
            > current.rule().strictness() ? mode.getValue() : current.rule();
        policies.upsert(userId, new AutonomyRuleDto(current == null ? null : current.id(),
            userId, agentId, mode.getKey().token(), rule, null, 10, null, null, null,
            lock || (current != null && current.locked())));
        tightened++;
      }
    }
    audit.record(userId, ActorType.USER, null, "agent_template.update_applied", agent.key(),
        Json.obj().put("templateId", template.id().toString()).put("version", template.version())
            .put("toolsAdded", newTools.size()).put("rulesTightened", tightened));
    return directory.byId(agentId).orElseThrow();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private boolean holds(UUID agentId, ToolRef tool) {
    return capabilities.grants(agentId).stream()
        .anyMatch(g -> g.type() == tool.type() && g.ref().equals(tool.ref()));
  }

  private List<ToolRef> usable(UUID userId, List<ToolRef> tools) {
    List<ToolRef> out = new ArrayList<>();
    for (ToolRef tool : tools) {
      try {
        custom.requireConnectorAccess(userId, tool);
        out.add(tool);
      } catch (PermissionDeniedException | NotFoundException ignored) {
        // Not this member's to use, or gone since the template was saved.
      }
    }
    return out;
  }

  private static AgentBlueprint withTools(AgentBlueprint b, List<ToolRef> tools) {
    return new AgentBlueprint(b.ref(), b.name(), b.description(), b.instructions(), b.avatar(),
        b.modelTier(), b.reportsTo(), b.responsibilities(), b.handsOffTo(), b.memoryDomains(),
        tools, b.toolHints(), b.modes(), b.locks());
  }

  private List<AgentBlueprint> validDefinition(UUID userId, UUID workspaceId, JsonNode definition) {
    List<AgentBlueprint> agents = AgentBlueprint.listFrom(definition);
    if (agents.isEmpty()) throw new ValidationException("A template needs at least one agent");
    if (agents.size() > CustomAgentUseCase.MAX_REPORTS * 2) {
      throw new ValidationException("A template is limited to "
          + (CustomAgentUseCase.MAX_REPORTS * 2) + " agents");
    }
    Set<String> refs = new java.util.HashSet<>();
    for (AgentBlueprint b : agents) {
      if (b.name() == null || b.name().isBlank()) {
        throw new ValidationException("Every agent in a template needs a name");
      }
      if (b.instructions() == null || b.instructions().isBlank()) {
        throw new ValidationException(b.name() + " needs instructions");
      }
      if (b.instructions().length() > CustomAgentUseCase.MAX_INSTRUCTIONS) {
        throw new ValidationException(b.name() + "'s instructions are too long");
      }
      if (b.ref() != null && !refs.add(b.ref())) {
        throw new ValidationException("Two agents in the template share the reference " + b.ref());
      }
      // A template only carries this organization's tools, and only ones its author may use.
      for (ToolRef tool : b.tools()) {
        custom.requireConnectorAccess(userId, tool);
      }
    }
    return agents;
  }

  private Template inWorkspace(UUID workspaceId, UUID templateId) {
    return templates.find(templateId)
        .filter(t -> t.workspaceId().equals(workspaceId))
        .orElseThrow(() -> new NotFoundException("Template", templateId));
  }

  private OrgTemplate view(Template t) {
    List<AgentBlueprint> agents = AgentBlueprint.listFrom(t.definition());
    String workspaceName = workspaces.workspace(t.workspaceId())
        .map(WorkspaceDirectory.Workspace::name).orElse(null);
    return new OrgTemplate(t.id(), t.workspaceId(), workspaceName, t.name(), t.description(),
        t.status(), t.version(), agents.size() > 1 ? "team" : "agent", agents);
  }

  private static String validName(String raw) {
    String name = trim(raw);
    if (name == null) throw new ValidationException("A template needs a name");
    if (name.length() > 80) throw new ValidationException("Template names are limited to 80 "
        + "characters");
    return name;
  }

  private static String trim(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
