package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.enums.CapabilityType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.orchestrator.AgentDependents;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.orchestrator.AgentRevision;
import com.jizza.engine.api.agent.CapabilityRegistry;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.platform.usecase.agent.AgentAdminUseCase;
import com.jizza.platform.usecase.agent.AgentBlueprint;
import com.jizza.platform.usecase.agent.AgentBlueprint.ToolRef;
import com.jizza.platform.usecase.agent.AgentTemplateUseCase;
import com.jizza.platform.usecase.agent.AgentWritingUseCase;
import com.jizza.platform.usecase.agent.CustomAgentUseCase;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.engine.security.session.SessionService;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The agent team: the built-ins and the agents people make themselves, how they're arranged in
 * teams, and what each may use (R21/R28). Reads only need a signed-in user; changes name the
 * permission they need, and whether a save needs a fresh re-authentication depends on what it
 * does (granting tools, loosening a rule, widening memory access), so the use case decides.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/agents")
public class AgentController {

  private final AgentDirectory directory;
  private final AgentAdminUseCase admin;
  private final CustomAgentUseCase custom;
  private final AgentTemplateUseCase templates;
  private final AgentWritingUseCase writing;
  private final CapabilityRegistry capabilities;
  private final PermissionEvaluator permissions;
  private final SessionService sessions;
  private final WorkspaceDirectory workspaces;

  public AgentController(AgentDirectory directory, AgentAdminUseCase admin,
                         CustomAgentUseCase custom, AgentTemplateUseCase templates,
                         AgentWritingUseCase writing, CapabilityRegistry capabilities,
                         PermissionEvaluator permissions, SessionService sessions,
                         WorkspaceDirectory workspaces) {
    this.directory = directory;
    this.admin = admin;
    this.custom = custom;
    this.templates = templates;
    this.writing = writing;
    this.capabilities = capabilities;
    this.permissions = permissions;
    this.sessions = sessions;
    this.workspaces = workspaces;
  }

  // ── Custom agents: what the screens send ─────────────────────────────────────

  public record ToolRefBody(@NotNull CapabilityType type, @NotNull UUID ref) {}

  public record AgentBody(String name, String description, String instructions, String modelTier,
                          List<String> memoryDomains, JsonNode avatar, Double monthlyCostLimit,
                          UUID parentAgentId, List<String> responsibilities, String handsOffTo,
                          Boolean memoryFromManager, String status, List<ToolRefBody> tools,
                          Map<ActionType, AutonomyRule> modes, Set<ActionType> locks) {}

  public record AgentEdit(String name, Boolean enabled, String description, String instructions,
                          String modelTier, List<String> memoryDomains, JsonNode avatar,
                          Double monthlyCostLimit, Boolean clearMonthlyCostLimit,
                          List<String> responsibilities, String handsOffTo,
                          Boolean memoryFromManager) {}

  public record TeamBody(String galleryId, JsonNode definition, UUID parentAgentId) {}

  public record ManagerBody(@NotNull UUID managerId) {}

  public record TryBody(@NotBlank @Size(max = 2000) String message) {}

  public record ImproveBody(String name, String description,
                            @NotBlank @Size(max = 4000) String instructions) {}

  public record Improved(String instructions) {}

  public record SuggestBody(String description, List<String> hints) {}

  public record Spend(double spentThisMonth, Double monthlyCostLimit) {}

  @GetMapping
  public List<AgentDto> list(@RequestParam(required = false) String status) {
    List<AgentDto> all = directory.list(CurrentUser.userId());
    if (status == null) {
      return all.stream().filter(a -> !"archived".equals(a.status())).toList();
    }
    if (status.equals("all")) return all;
    return all.stream().filter(a -> status.equals(a.status())).toList();
  }

  @PostMapping
  @RequiresPermission(Permissions.AGENT_CREATE)
  public AgentDto create(@RequestBody AgentBody body) {
    return custom.create(CurrentUser.userId(), input(body), this::stepUpFresh);
  }

  /** Adds a gallery team, or one Jizza proposed, in one go. */
  @PostMapping("/teams")
  @RequiresPermission(Permissions.AGENT_CREATE)
  public List<AgentDto> createTeam(@RequestBody TeamBody body) {
    UUID userId = CurrentUser.userId();
    if (body.galleryId() != null) {
      return templates.installGallery(userId, body.galleryId(), body.parentAgentId(),
          this::stepUpFresh);
    }
    if (body.definition() == null) {
      throw new ValidationException("Send a gallery id or the team's definition");
    }
    return custom.createTeam(userId, AgentBlueprint.listFrom(body.definition()),
        body.parentAgentId(), null, null, this::stepUpFresh);
  }

  @GetMapping("/tree")
  public CustomAgentUseCase.TeamNode tree() {
    return custom.tree(CurrentUser.userId());
  }

  @GetMapping("/gallery")
  public List<AgentTemplateUseCase.GalleryItem> gallery() {
    return templates.gallery();
  }

  /** Every tool the user may hand an agent, with the kind of action each one is. */
  @GetMapping("/tool-options")
  public List<CustomAgentUseCase.ToolOption> toolOptions() {
    return custom.toolOptions(CurrentUser.userId(), workspaceIds());
  }

  @PostMapping("/tool-suggestions")
  public List<CustomAgentUseCase.ToolOption> suggest(@RequestBody SuggestBody body) {
    return CustomAgentUseCase.suggest(custom.toolOptions(CurrentUser.userId(), workspaceIds()),
        body.description(), body.hints(), 8);
  }

  /** "Improve these": a rewrite to show beside the original. Nothing is saved. */
  @PostMapping("/improve-instructions")
  @RequiresPermission(Permissions.AGENT_MODIFY)
  public Improved improve(@Valid @RequestBody ImproveBody body) {
    return new Improved(writing.improve(CurrentUser.userId(), body.name(), body.description(),
        body.instructions()));
  }

  @GetMapping("/{id}")
  public AgentDto get(@PathVariable UUID id) {
    return owned(id);
  }

  @PatchMapping("/{id}")
  public AgentDto patch(@PathVariable UUID id, @RequestBody AgentEdit patch) {
    AgentDto agent = owned(id);
    UUID userId = CurrentUser.userId();
    AgentDto result = null;
    boolean customFields = patch.description() != null || patch.instructions() != null
        || patch.modelTier() != null || patch.memoryDomains() != null || patch.avatar() != null
        || patch.monthlyCostLimit() != null || Boolean.TRUE.equals(patch.clearMonthlyCostLimit())
        || patch.responsibilities() != null || patch.handsOffTo() != null
        || patch.memoryFromManager() != null;
    if (customFields || (agent.custom() && patch.name() != null)) {
      permissions.require(userId, CurrentWorkspace.id(), Permissions.AGENT_MODIFY);
      result = custom.update(userId, id, new CustomAgentUseCase.Patch(patch.name(),
          patch.description(), patch.instructions(), patch.modelTier(), patch.memoryDomains(),
          patch.avatar(), patch.monthlyCostLimit(),
          Boolean.TRUE.equals(patch.clearMonthlyCostLimit()), patch.responsibilities(),
          patch.handsOffTo(), patch.memoryFromManager()), this::stepUpFresh);
    } else if (patch.name() != null) {
      result = admin.rename(userId, id, patch.name());
    }
    if (patch.enabled() != null) result = admin.setEnabled(userId, id, patch.enabled());
    return result == null ? owned(id) : result;
  }

  @PostMapping("/{id}/activate")
  @RequiresPermission(Permissions.AGENT_MODIFY)
  public AgentDto activate(@PathVariable UUID id) {
    return custom.activate(CurrentUser.userId(), id);
  }

  @GetMapping("/{id}/revisions")
  public List<AgentRevision> revisions(@PathVariable UUID id) {
    return custom.revisions(CurrentUser.userId(), id);
  }

  @PostMapping("/{id}/revisions/{revision}/restore")
  @RequiresPermission(Permissions.AGENT_MODIFY)
  public AgentDto restoreRevision(@PathVariable UUID id, @PathVariable int revision) {
    return custom.restoreRevision(CurrentUser.userId(), id, revision);
  }

  @GetMapping("/{id}/dependents")
  public List<AgentDependents.Dependent> dependents(@PathVariable UUID id) {
    return custom.dependents(CurrentUser.userId(), id);
  }

  @PostMapping("/{id}/archive")
  @RequiresPermission(Permissions.AGENT_ARCHIVE)
  public CustomAgentUseCase.ArchiveResult archive(
      @PathVariable UUID id, @RequestBody(required = false) CustomAgentUseCase.ArchiveOptions options) {
    return custom.archive(CurrentUser.userId(), id, options);
  }

  @PostMapping("/{id}/restore")
  @RequiresPermission(Permissions.AGENT_ARCHIVE)
  public AgentDto restore(@PathVariable UUID id) {
    return custom.restore(CurrentUser.userId(), id);
  }

  /** For good, and with its private memory: only an archived agent nothing uses. */
  @DeleteMapping("/{id}")
  @RequiresPermission(Permissions.AGENT_ARCHIVE)
  @StepUpRequired
  public Responses.Message delete(@PathVariable UUID id) {
    custom.delete(CurrentUser.userId(), id);
    return new Responses.Message("Agent deleted.");
  }

  @PutMapping("/{id}/manager")
  @RequiresPermission(Permissions.AGENT_MODIFY)
  public AgentDto setManager(@PathVariable UUID id, @Valid @RequestBody ManagerBody body) {
    return custom.setManager(CurrentUser.userId(), id, body.managerId(), this::stepUpFresh);
  }

  /** A trial run: real for reading, held for anything that would change something. */
  @PostMapping("/{id}/try")
  @RequiresPermission(Permissions.AGENT_MODIFY)
  public CustomAgentUseCase.Trial tryOut(@PathVariable UUID id, @Valid @RequestBody TryBody body) {
    return custom.tryOut(CurrentUser.userId(), id, body.message());
  }

  @GetMapping("/{id}/modes")
  public List<CustomAgentUseCase.ModeLine> modes(@PathVariable UUID id) {
    return custom.modes(CurrentUser.userId(), id);
  }

  @GetMapping("/{id}/spend")
  public Spend spend(@PathVariable UUID id) {
    AgentDto agent = owned(id);
    return new Spend(custom.spentThisMonth(CurrentUser.userId(), id), agent.monthlyCostLimit());
  }

  @GetMapping("/{id}/tools")
  public List<com.jizza.engine.api.tool.ToolSpec> tools(@PathVariable UUID id) {
    owned(id);
    return capabilities.toolsFor(id);
  }

  @GetMapping("/{id}/capabilities")
  public List<CapabilityRegistry.Grant> grants(@PathVariable UUID id) {
    owned(id);
    return capabilities.grants(id);
  }

  /** Assigning a capability widens what an agent can do, so it needs a fresh re-authentication. */
  @PostMapping("/{id}/capabilities")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message grant(@PathVariable UUID id,
                                 @Valid @RequestBody Requests.CapabilityGrant grant) {
    owned(id);
    custom.requireConnectorAccess(CurrentUser.userId(),
        new ToolRef(grant.type(), grant.capabilityRef()));
    capabilities.grant(id, grant.type(), grant.capabilityRef());
    return new Responses.Message("Capability assigned.");
  }

  @DeleteMapping("/{id}/capabilities/{type}/{ref}")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message revoke(@PathVariable UUID id, @PathVariable CapabilityType type,
                                  @PathVariable UUID ref) {
    owned(id);
    capabilities.revoke(id, type, ref);
    return new Responses.Message("Capability removed.");
  }

  private boolean stepUpFresh() {
    return sessions.stepUpFresh(CurrentUser.sessionId());
  }

  private List<UUID> workspaceIds() {
    return workspaces.membershipsOf(CurrentUser.userId()).stream()
        .filter(Membership::active).map(Membership::workspaceId).toList();
  }

  private static CustomAgentUseCase.Input input(AgentBody b) {
    List<ToolRef> tools = b.tools() == null ? List.of()
        : b.tools().stream().map(t -> new ToolRef(t.type(), t.ref())).toList();
    return new CustomAgentUseCase.Input(b.name(), b.description(), b.instructions(),
        b.modelTier(), b.memoryDomains(), b.avatar(), b.monthlyCostLimit(), b.parentAgentId(),
        b.responsibilities(), b.handsOffTo(), b.memoryFromManager(), b.status(), tools,
        b.modes(), b.locks(), null, null);
  }

  /** Every lookup is scoped to the signed-in user; another user's agent is simply not found. */
  private AgentDto owned(UUID agentId) {
    return directory.byId(agentId)
        .filter(agent -> agent.userId().equals(CurrentUser.userId()))
        .orElseThrow(() -> new NotFoundException("Agent", agentId));
  }
}
