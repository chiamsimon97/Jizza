package com.jizza.platform.api.rest;

import com.jizza.engine.autonomy.policy.AutonomyPolicyService;
import com.jizza.engine.api.autonomy.KillSwitch;
import com.jizza.engine.api.dto.AutonomyRuleDto;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.validation.Valid;
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
 * Autonomy and control (R40/R41). Every route here changes what agents may do unattended, so all of
 * them require a fresh re-authentication.
 */
@RestController
@RequestMapping("/api/autonomy")
@com.jizza.engine.api.util.StepUpRequired
public class AutonomyController {

  private final AutonomyPolicyService policies;
  private final KillSwitch killSwitch;

  public AutonomyController(AutonomyPolicyService policies, KillSwitch killSwitch) {
    this.policies = policies;
    this.killSwitch = killSwitch;
  }

  /** Returns the explicit rules and the default that applies when none of them match. */
  @GetMapping("/policies")
  public Responses.AutonomySettings policies() {
    UUID userId = CurrentUser.userId();
    return new Responses.AutonomySettings(policies.list(userId),
        AutonomyPolicyService.defaultRuleDescription(), killSwitch.paused(userId));
  }

  @PutMapping("/policies")
  public AutonomyRuleDto upsert(@Valid @RequestBody Requests.AutonomyPolicy request) {
    return policies.upsert(CurrentUser.userId(), new AutonomyRuleDto(request.id(),
        CurrentUser.userId(), request.agentId(), request.toolRef(), request.rule(),
        request.limits(), request.precedence()));
  }

  @DeleteMapping("/policies/{id}")
  public Responses.Message delete(@PathVariable UUID id) {
    policies.delete(CurrentUser.userId(), id);
    return new Responses.Message("Rule removed. The conservative default applies again.");
  }

  @PostMapping("/kill-switch")
  public Responses.Message killSwitch(@Valid @RequestBody Requests.KillSwitch request) {
    killSwitch.setPaused(CurrentUser.userId(), request.paused());
    return new Responses.Message(request.paused()
        ? "Autonomy paused. Running work halts at its next checkpoint and nothing new starts."
        : "Autonomy resumed. Paused runs pick up from where they stopped.");
  }
}
