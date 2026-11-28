package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.vita.insurance.VitaInsuranceUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health insurance and claims (spec 5.12, Phase 2b), behind the health switch and not the finance
 * one: policies are the household's (the manager's to change), claims are a person's. Policy and
 * card numbers go in and only ever come back masked.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaInsuranceController {

  private final VitaInsuranceUseCase insurance;

  public VitaInsuranceController(VitaInsuranceUseCase insurance) {
    this.insurance = insurance;
  }

  /** The household's health policies; with {@code personId}, those covering that person. */
  @GetMapping("/policies")
  public List<ObjectNode> policies(@RequestParam(value = "personId", required = false) UUID personId) {
    return insurance.policies(CurrentUser.userId(), personId);
  }

  @GetMapping("/policies/{id}")
  public ObjectNode policy(@PathVariable UUID id) {
    return insurance.policy(CurrentUser.userId(), id);
  }

  @PostMapping("/policies")
  public ObjectNode addPolicy(@RequestBody JsonNode body) {
    return insurance.addPolicy(CurrentUser.userId(), body);
  }

  @PutMapping("/policies/{id}")
  public ObjectNode updatePolicy(@PathVariable UUID id, @RequestBody JsonNode body) {
    return insurance.updatePolicy(CurrentUser.userId(), id, body);
  }

  @DeleteMapping("/policies/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePolicy(@PathVariable UUID id) {
    insurance.deletePolicy(CurrentUser.userId(), id);
  }

  @GetMapping("/policies/{id}/detail")
  public ObjectNode detail(@PathVariable UUID id) {
    return insurance.detail(CurrentUser.userId(), id);
  }

  @PutMapping("/policies/{id}/detail")
  public ObjectNode putDetail(@PathVariable UUID id, @RequestBody JsonNode body) {
    return insurance.putDetail(CurrentUser.userId(), id, body);
  }

  @GetMapping("/people/{personId}/claims")
  public List<ObjectNode> claims(@PathVariable UUID personId) {
    return insurance.claims(CurrentUser.userId(), personId);
  }

  @PostMapping("/people/{personId}/claims")
  public ObjectNode addClaim(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return insurance.addClaim(CurrentUser.userId(), personId, body);
  }

  @PutMapping("/people/{personId}/claims/{id}")
  public ObjectNode updateClaim(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return insurance.updateClaim(CurrentUser.userId(), personId, id, body);
  }

  @DeleteMapping("/people/{personId}/claims/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteClaim(@PathVariable UUID personId, @PathVariable UUID id) {
    insurance.deleteClaim(CurrentUser.userId(), personId, id);
  }
}
