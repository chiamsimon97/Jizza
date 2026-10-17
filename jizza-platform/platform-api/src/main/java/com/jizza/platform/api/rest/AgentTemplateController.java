package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.engine.security.session.SessionService;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.agent.AgentTemplateUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent templates an organization shares with its members. Admins manage them in the active
 * organization; a member adds one to their own team from whichever organization published it.
 */
@RestController
public class AgentTemplateController {

  public record TemplateBody(String name, String description, JsonNode definition) {}

  public record InstallBody(UUID parentAgentId) {}

  public record UnpublishBody(boolean pauseCopies) {}

  private final AgentTemplateUseCase templates;
  private final SessionService sessions;

  public AgentTemplateController(AgentTemplateUseCase templates, SessionService sessions) {
    this.templates = templates;
    this.sessions = sessions;
  }

  // ── Admins, in the active organization ──────────────────────────────────────

  @RequiresPermission(Permissions.AGENT_TEMPLATE_MANAGE)
  @GetMapping("/api/organization/agent-templates")
  public List<AgentTemplateUseCase.OrgTemplate> list() {
    return templates.list(CurrentWorkspace.id());
  }

  @RequiresPermission(Permissions.AGENT_TEMPLATE_MANAGE)
  @PostMapping("/api/organization/agent-templates")
  public AgentTemplateUseCase.OrgTemplate create(@RequestBody TemplateBody body) {
    return templates.create(CurrentUser.userId(), CurrentWorkspace.id(), body.name(),
        body.description(), body.definition());
  }

  @RequiresPermission(Permissions.AGENT_TEMPLATE_MANAGE)
  @PutMapping("/api/organization/agent-templates/{id}")
  public AgentTemplateUseCase.OrgTemplate update(@PathVariable UUID id,
                                                 @RequestBody TemplateBody body) {
    return templates.update(CurrentUser.userId(), CurrentWorkspace.id(), id, body.name(),
        body.description(), body.definition());
  }

  /** Publishing hands a definition to every member, so it needs a fresh re-authentication. */
  @RequiresPermission(Permissions.AGENT_TEMPLATE_MANAGE)
  @StepUpRequired
  @PostMapping("/api/organization/agent-templates/{id}/publish")
  public AgentTemplateUseCase.OrgTemplate publish(@PathVariable UUID id) {
    return templates.publish(CurrentUser.userId(), CurrentWorkspace.id(), id);
  }

  @RequiresPermission(Permissions.AGENT_TEMPLATE_MANAGE)
  @PostMapping("/api/organization/agent-templates/{id}/unpublish")
  public AgentTemplateUseCase.OrgTemplate unpublish(@PathVariable UUID id,
                                                    @RequestBody(required = false)
                                                    UnpublishBody body) {
    return templates.unpublish(CurrentUser.userId(), CurrentWorkspace.id(), id,
        body != null && body.pauseCopies());
  }

  @RequiresPermission(Permissions.AGENT_TEMPLATE_MANAGE)
  @DeleteMapping("/api/organization/agent-templates/{id}")
  public Responses.Message delete(@PathVariable UUID id) {
    templates.delete(CurrentUser.userId(), CurrentWorkspace.id(), id);
    return new Responses.Message("Template deleted.");
  }

  // ── Members, from any organization they belong to ────────────────────────────

  /** Published templates from every organization the user may see them in. */
  @AuthenticatedOnly
  @GetMapping("/api/agent-templates")
  public List<AgentTemplateUseCase.OrgTemplate> available() {
    return templates.available(CurrentUser.userId());
  }

  @AuthenticatedOnly
  @PostMapping("/api/agent-templates/{id}/install")
  public List<AgentDto> install(@PathVariable UUID id,
                                @RequestBody(required = false) InstallBody body) {
    return templates.install(CurrentUser.userId(), id, body == null ? null : body.parentAgentId(),
        this::stepUpFresh);
  }

  @AuthenticatedOnly
  @GetMapping("/api/agent-templates/updates")
  public List<AgentTemplateUseCase.UpdateAvailable> updates() {
    return templates.updates(CurrentUser.userId());
  }

  @AuthenticatedOnly
  @PostMapping("/api/agents/{agentId}/template-update")
  public AgentDto applyUpdate(@PathVariable UUID agentId) {
    return templates.applyUpdate(CurrentUser.userId(), agentId, this::stepUpFresh);
  }

  private boolean stepUpFresh() {
    return sessions.stepUpFresh(CurrentUser.sessionId());
  }
}
