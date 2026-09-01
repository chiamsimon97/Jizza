package com.jizza.platform.api.rest;

import com.jizza.platform.usecase.autonomy.AutonomyUseCase;
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

  private final AutonomyUseCase autonomy;

  public AutonomyController(AutonomyUseCase autonomy) {
    this.autonomy = autonomy;
  }

  /** Returns the explicit rules and the default that applies when none of them match. */
  @GetMapping("/policies")
  public Responses.AutonomySettings policies() {
    UUID userId = CurrentUser.userId();
    return new Responses.AutonomySettings(autonomy.list(userId),
        autonomy.defaultRuleDescription(), autonomy.paused(userId));
  }

  @PutMapping("/policies")
  public AutonomyRuleDto upsert(@Valid @RequestBody Requests.AutonomyPolicy request) {
    return autonomy.upsert(CurrentUser.userId(), new AutonomyRuleDto(request.id(),
        CurrentUser.userId(), request.agentId(), request.toolRef(), request.rule(),
        request.limits(), request.precedence()));
  }

  @DeleteMapping("/policies/{id}")
  public Responses.Message delete(@PathVariable UUID id) {
    autonomy.delete(CurrentUser.userId(), id);
    return new Responses.Message("Rule removed. The conservative default applies again.");
  }

  @PostMapping("/kill-switch")
  public Responses.Message killSwitch(@Valid @RequestBody Requests.KillSwitch request) {
    autonomy.setPaused(CurrentUser.userId(), request.paused());
    return new Responses.Message(request.paused()
        ? "Autonomy paused. Running work halts at its next checkpoint and nothing new starts."
        : "Autonomy resumed. Paused runs pick up from where they stopped.");
  }
}
