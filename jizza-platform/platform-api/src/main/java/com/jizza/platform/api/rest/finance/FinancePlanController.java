package com.jizza.platform.api.rest.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.finance.builder.FinBuilderUseCase;
import com.jizza.platform.usecase.finance.goal.FinGoalUseCase;
import com.jizza.platform.usecase.finance.risk.FinRiskUseCase;
import com.jizza.platform.usecase.finance.tax.FinTaxUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Kai Portfolio, Phase 1b: goals and the goal library, the plan builder, the risk profile and the
 * tax regime comparison. The caller's own data only; another account's ids read as not found.
 * Nothing here buys, sells or schedules anything. Exists only while {@code jizza.finance.enabled}
 * is on.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/finance")
@ConditionalOnProperty(name = "jizza.finance.enabled", havingValue = "true")
public class FinancePlanController {

  private final FinGoalUseCase goals;
  private final FinBuilderUseCase builder;
  private final FinRiskUseCase risk;
  private final FinTaxUseCase tax;

  public FinancePlanController(FinGoalUseCase goals, FinBuilderUseCase builder, FinRiskUseCase risk,
                               FinTaxUseCase tax) {
    this.goals = goals;
    this.builder = builder;
    this.risk = risk;
    this.tax = tax;
  }

  // ── Goals ───────────────────────────────────────────────────────────────────

  @GetMapping("/goal-library")
  public List<FinGoalUseCase.LibraryCard> library() {
    return goals.library(CurrentUser.userId());
  }

  @GetMapping("/goals")
  public List<FinGoalUseCase.GoalView> goals() {
    return goals.list(CurrentUser.userId());
  }

  @GetMapping("/goals/{id}")
  public FinGoalUseCase.GoalView goal(@PathVariable UUID id) {
    return goals.get(CurrentUser.userId(), id);
  }

  @PostMapping("/goals")
  public FinGoalUseCase.GoalView addGoal(@RequestBody JsonNode body) {
    return goals.create(CurrentUser.userId(), body, "wizard");
  }

  @PatchMapping("/goals/{id}")
  public FinGoalUseCase.GoalView updateGoal(@PathVariable UUID id, @RequestBody JsonNode body) {
    return goals.update(CurrentUser.userId(), id, body);
  }

  @PostMapping("/goals/{id}/achieve")
  public FinGoalUseCase.GoalView achieve(@PathVariable UUID id) {
    return goals.achieve(CurrentUser.userId(), id);
  }

  // ── Plan builder ────────────────────────────────────────────────────────────

  @GetMapping("/builder")
  public FinBuilderUseCase.BuilderView builder() {
    return builder.get(CurrentUser.userId());
  }

  @PutMapping("/builder")
  public FinBuilderUseCase.BuilderView putBuilder(@RequestBody JsonNode body) {
    return builder.put(CurrentUser.userId(), body);
  }

  @PostMapping("/builder/preview")
  public FinBuilderUseCase.BuilderView preview(@RequestBody JsonNode body) {
    return builder.preview(CurrentUser.userId(), body);
  }

  @PostMapping("/builder/save")
  public FinBuilderUseCase.BuilderView save(@RequestBody JsonNode body) {
    return builder.save(CurrentUser.userId(), body);
  }

  // ── Risk profile ────────────────────────────────────────────────────────────

  public record RiskBody(JsonNode answers) {}

  @GetMapping("/risk/{personId}")
  public FinRiskUseCase.RiskView risk(@PathVariable UUID personId) {
    return risk.get(CurrentUser.userId(), personId);
  }

  @PostMapping("/risk/{personId}")
  public ObjectNode assess(@PathVariable UUID personId, @RequestBody RiskBody body) {
    return risk.assess(CurrentUser.userId(), personId, body == null ? null : body.answers());
  }

  // ── Tax ─────────────────────────────────────────────────────────────────────

  @GetMapping("/tax/{personId}")
  public FinTaxUseCase.TaxView tax(@PathVariable UUID personId, @RequestParam(required = false) String year) {
    return tax.get(CurrentUser.userId(), personId, year);
  }

  @PutMapping("/tax/{personId}")
  public FinTaxUseCase.TaxView putTax(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return tax.put(CurrentUser.userId(), personId, body);
  }
}
