package com.jizza.platform.api.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.autonomy.GuardrailSettingsStore.QuietHours;
import com.jizza.engine.api.dto.AutonomyRuleDto;
import com.jizza.engine.api.enums.AutonomyRule;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.api.util.StepUpRequired;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.autonomy.AutonomyUseCase;
import com.jizza.platform.usecase.autonomy.GuardrailsUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guardrails: what each agent may do on its own, per workflow and per step (R41).
 *
 * <p>Looking is free; every change asks the user to re-authenticate, because a loosened guardrail
 * is worth more to an attacker than most of the data behind it. The older {@code /api/autonomy}
 * routes keep working for the kill switch and existing clients.
 */
@RestController
@RequestMapping("/api/guardrails")
public class GuardrailsController {

  /**
   * A rule as the screen sends it. With {@code workflowId} it covers that workflow's current
   * version only, and with {@code nodeId} only that step.
   */
  public record Rule(UUID id, UUID agentId, String toolRef, @NotNull AutonomyRule rule,
                     JsonNode limits, int precedence, UUID workflowId, String nodeId,
                     boolean locked) {}

  /** An organization lock: Ask me or Never for a kind of action on its connectors. */
  public record OrganizationLock(UUID id, @NotBlank String toolRef, @NotNull AutonomyRule rule) {}

  public record Simulation(@NotNull UUID agentId, @NotBlank String toolRef, JsonNode args,
                           UUID workflowId, String nodeId) {}

  /** The agents that maintain a workflow, lead first; empty goes back to the owner or Jizza. */
  public record MaintainersRequest(List<UUID> agentIds) {}

  public record QuietHoursRequest(LocalTime start, LocalTime end, String timeZone) {}

  private final GuardrailsUseCase guardrails;
  private final AutonomyUseCase autonomy;

  public GuardrailsController(GuardrailsUseCase guardrails, AutonomyUseCase autonomy) {
    this.guardrails = guardrails;
    this.autonomy = autonomy;
  }

  @AuthenticatedOnly
  @GetMapping
  public GuardrailsUseCase.Overview overview() {
    UUID userId = CurrentUser.userId();
    return guardrails.overview(userId, autonomy.defaultRuleDescription(), autonomy.paused(userId));
  }

  @AuthenticatedOnly
  @GetMapping("/workflows/{workflowId}")
  public GuardrailsUseCase.WorkflowGuardrails workflow(@PathVariable UUID workflowId) {
    return guardrails.workflow(CurrentUser.userId(), workflowId);
  }

  /** Changing whose guardrails judge a workflow's runs can loosen them, so it needs a fresh sign-in. */
  @AuthenticatedOnly
  @StepUpRequired
  @PutMapping("/workflows/{workflowId}/maintainers")
  public GuardrailsUseCase.WorkflowGuardrails maintainers(@PathVariable UUID workflowId,
                                                          @RequestBody MaintainersRequest request) {
    return guardrails.setMaintainers(CurrentUser.userId(), workflowId, request.agentIds());
  }

  /** What would happen if this call were made now. Nothing runs and nothing is spent. */
  @AuthenticatedOnly
  @PostMapping("/simulate")
  public Decision simulate(@Valid @RequestBody Simulation request) {
    return guardrails.simulate(CurrentUser.userId(), request.agentId(), request.toolRef(),
        request.args(), request.workflowId(), request.nodeId());
  }

  @AuthenticatedOnly
  @StepUpRequired
  @PutMapping("/rules")
  public AutonomyRuleDto upsert(@Valid @RequestBody Rule request) {
    UUID userId = CurrentUser.userId();
    AutonomyRuleDto rule = new AutonomyRuleDto(request.id(), userId, request.agentId(),
        request.toolRef(), request.rule(), request.limits(), request.precedence(), null, null,
        blankToNull(request.nodeId()), request.locked());
    return request.workflowId() == null
        ? autonomy.upsert(userId, rule)
        : guardrails.upsertForWorkflow(userId, request.workflowId(), rule);
  }

  @AuthenticatedOnly
  @StepUpRequired
  @DeleteMapping("/rules/{id}")
  public Responses.Message delete(@PathVariable UUID id) {
    autonomy.delete(CurrentUser.userId(), id);
    return new Responses.Message("Rule removed. The next rule, or the safe default, applies.");
  }

  @AuthenticatedOnly
  @StepUpRequired
  @PutMapping("/quiet-hours")
  public QuietHours quietHours(@RequestBody QuietHoursRequest request) {
    return guardrails.saveQuietHours(CurrentUser.userId(),
        new QuietHours(request.start(), request.end(), request.timeZone()));
  }

  @RequiresPermission(Permissions.GUARDRAILS_VIEW)
  @GetMapping("/organization")
  public List<AutonomyRuleDto> organizationLocks() {
    return guardrails.organizationLocks(CurrentWorkspace.id());
  }

  @RequiresPermission(Permissions.GUARDRAILS_LOCK)
  @StepUpRequired
  @PutMapping("/organization")
  public AutonomyRuleDto lock(@Valid @RequestBody OrganizationLock request) {
    return guardrails.lockForOrganization(CurrentUser.userId(), CurrentWorkspace.id(),
        new AutonomyRuleDto(request.id(), CurrentUser.userId(), null, request.toolRef(),
            request.rule(), null, 0, null, null, null, true));
  }

  @RequiresPermission(Permissions.GUARDRAILS_LOCK)
  @StepUpRequired
  @DeleteMapping("/organization/{id}")
  public Responses.Message unlock(@PathVariable UUID id) {
    guardrails.unlockForOrganization(CurrentUser.userId(), CurrentWorkspace.id(), id);
    return new Responses.Message("Organization lock removed.");
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
