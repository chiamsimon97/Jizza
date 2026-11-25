package com.jizza.platform.api.rest.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.finance.cashflow.FinCashFlowUseCase;
import com.jizza.platform.usecase.finance.debt.FinLoansUseCase;
import com.jizza.platform.usecase.finance.health.FinHealthUseCase;
import com.jizza.platform.usecase.finance.protection.FinProtectionUseCase;
import com.jizza.platform.usecase.finance.quickstart.QuickStartUseCase;
import com.jizza.platform.usecase.finance.records.FinRecordsUseCase;
import com.jizza.platform.usecase.finance.records.FinTable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kai Portfolio, Phase 1a: Money Health with nothing connected. Quick start, accounts and assets,
 * income and budget, cash flow and net worth, loans and money lent, insurance and protection,
 * wills, the health check and the action plan. Every route is the caller's own data only; another
 * account's ids read as not found. Exists only while {@code jizza.finance.enabled} is on.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/finance")
@ConditionalOnProperty(name = "jizza.finance.enabled", havingValue = "true")
public class FinanceMoneyController {

  private final QuickStartUseCase quickStart;
  private final FinRecordsUseCase forms;
  private final FinCashFlowUseCase cashFlow;
  private final FinLoansUseCase loans;
  private final FinProtectionUseCase protection;
  private final FinHealthUseCase health;

  public FinanceMoneyController(QuickStartUseCase quickStart, FinRecordsUseCase forms,
                                FinCashFlowUseCase cashFlow, FinLoansUseCase loans,
                                FinProtectionUseCase protection, FinHealthUseCase health) {
    this.quickStart = quickStart;
    this.forms = forms;
    this.cashFlow = cashFlow;
    this.loans = loans;
    this.protection = protection;
    this.health = health;
  }

  // ── Quick start ─────────────────────────────────────────────────────────────

  public record QuickStartBody(@NotNull @Size(max = 20) String step, JsonNode answers, Boolean skip) {}

  public record ProgressView(QuickStartUseCase.Progress progress) {}

  @GetMapping("/quick-start")
  public QuickStartUseCase.QuickStartView quickStart() {
    return quickStart.get(CurrentUser.userId());
  }

  @PostMapping("/quick-start")
  public ProgressView saveQuickStart(@Valid @RequestBody QuickStartBody body) {
    return new ProgressView(quickStart.save(CurrentUser.userId(), body.step(), body.answers(),
        Boolean.TRUE.equals(body.skip())));
  }

  // ── Accounts and assets ─────────────────────────────────────────────────────

  @GetMapping("/accounts")
  public List<ObjectNode> accounts() {
    return forms.list(CurrentUser.userId(), FinTable.ACCOUNT, Map.of());
  }

  @GetMapping("/accounts/{id}")
  public ObjectNode account(@PathVariable UUID id) {
    return forms.get(CurrentUser.userId(), FinTable.ACCOUNT, id);
  }

  @PostMapping("/accounts")
  public ObjectNode addAccount(@RequestBody JsonNode body) {
    return forms.create(CurrentUser.userId(), FinTable.ACCOUNT, body, null);
  }

  @PatchMapping("/accounts/{id}")
  public ObjectNode updateAccount(@PathVariable UUID id, @RequestBody JsonNode body) {
    return forms.update(CurrentUser.userId(), FinTable.ACCOUNT, id, body);
  }

  @DeleteMapping("/accounts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteAccount(@PathVariable UUID id) {
    forms.delete(CurrentUser.userId(), FinTable.ACCOUNT, id);
  }

  @GetMapping("/assets")
  public List<ObjectNode> assets() {
    return forms.list(CurrentUser.userId(), FinTable.ASSET, Map.of());
  }

  @GetMapping("/assets/{id}")
  public ObjectNode asset(@PathVariable UUID id) {
    return forms.get(CurrentUser.userId(), FinTable.ASSET, id);
  }

  @PostMapping("/assets")
  public ObjectNode addAsset(@RequestBody JsonNode body) {
    return forms.create(CurrentUser.userId(), FinTable.ASSET, body, null);
  }

  @PatchMapping("/assets/{id}")
  public ObjectNode updateAsset(@PathVariable UUID id, @RequestBody JsonNode body) {
    return forms.update(CurrentUser.userId(), FinTable.ASSET, id, body);
  }

  @DeleteMapping("/assets/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteAsset(@PathVariable UUID id) {
    forms.delete(CurrentUser.userId(), FinTable.ASSET, id);
  }

  // ── Income, budget, categories, cash flow, net worth ────────────────────────

  @GetMapping("/income")
  public List<ObjectNode> income() {
    return cashFlow.income(CurrentUser.userId());
  }

  /** Replaces the income lines with the list sent. */
  @PutMapping("/income")
  public List<ObjectNode> putIncome(@RequestBody JsonNode lines) {
    return cashFlow.putIncome(CurrentUser.userId(), lines);
  }

  @GetMapping("/budget")
  public List<ObjectNode> budget() {
    return cashFlow.budget(CurrentUser.userId());
  }

  /** Replaces the budget with the list sent: one {categoryId, monthlyAmount} per category. */
  @PutMapping("/budget")
  public List<ObjectNode> putBudget(@RequestBody JsonNode lines) {
    return cashFlow.putBudget(CurrentUser.userId(), lines);
  }

  @GetMapping("/categories")
  public List<ObjectNode> categories() {
    return cashFlow.categories(CurrentUser.userId());
  }

  @GetMapping("/cash-flow")
  public FinCashFlowUseCase.CashFlowView cashFlow(@RequestParam(required = false) String month) {
    return cashFlow.cashFlow(CurrentUser.userId(), month);
  }

  @GetMapping("/net-worth")
  public FinCashFlowUseCase.NetWorthView netWorth(@RequestParam(required = false) UUID person) {
    return cashFlow.netWorth(CurrentUser.userId(), person);
  }

  // ── Loans and money lent ────────────────────────────────────────────────────

  @GetMapping("/loans")
  public List<ObjectNode> loans() {
    return loans.list(CurrentUser.userId());
  }

  @GetMapping("/loans/plan")
  public FinLoansUseCase.PlanView loanPlan(@RequestParam(required = false) String method,
                                           @RequestParam(required = false) BigDecimal extra) {
    return loans.plan(CurrentUser.userId(), method, extra);
  }

  @GetMapping("/loans/{id}")
  public ObjectNode loan(@PathVariable UUID id) {
    return loans.get(CurrentUser.userId(), id);
  }

  @PostMapping("/loans")
  public ObjectNode addLoan(@RequestBody JsonNode body) {
    return loans.create(CurrentUser.userId(), body);
  }

  @PatchMapping("/loans/{id}")
  public ObjectNode updateLoan(@PathVariable UUID id, @RequestBody JsonNode body) {
    return loans.update(CurrentUser.userId(), id, body);
  }

  @DeleteMapping("/loans/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteLoan(@PathVariable UUID id) {
    loans.delete(CurrentUser.userId(), id);
  }

  @GetMapping("/loans/{id}/payments")
  public List<ObjectNode> payments(@PathVariable UUID id) {
    return loans.payments(CurrentUser.userId(), id);
  }

  @PostMapping("/loans/{id}/payments")
  public ObjectNode addPayment(@PathVariable UUID id, @RequestBody JsonNode body) {
    return loans.addPayment(CurrentUser.userId(), id, body);
  }

  @GetMapping("/loans-given")
  public List<ObjectNode> loansGiven() {
    return loans.given(CurrentUser.userId());
  }

  @GetMapping("/loans-given/{id}")
  public ObjectNode loanGiven(@PathVariable UUID id) {
    return forms.get(CurrentUser.userId(), FinTable.LOAN_GIVEN, id);
  }

  @PostMapping("/loans-given")
  public ObjectNode addLoanGiven(@RequestBody JsonNode body) {
    return loans.addGiven(CurrentUser.userId(), body);
  }

  @PatchMapping("/loans-given/{id}")
  public ObjectNode updateLoanGiven(@PathVariable UUID id, @RequestBody JsonNode body) {
    return loans.updateGiven(CurrentUser.userId(), id, body);
  }

  @DeleteMapping("/loans-given/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteLoanGiven(@PathVariable UUID id) {
    loans.deleteGiven(CurrentUser.userId(), id);
  }

  // ── Insurance, protection, wills ────────────────────────────────────────────

  @GetMapping("/policies")
  public List<ObjectNode> policies() {
    return protection.policies(CurrentUser.userId());
  }

  @GetMapping("/policies/{id}")
  public ObjectNode policy(@PathVariable UUID id) {
    return forms.get(CurrentUser.userId(), FinTable.POLICY, id);
  }

  @PostMapping("/policies")
  public ObjectNode addPolicy(@RequestBody JsonNode body) {
    return protection.addPolicy(CurrentUser.userId(), body);
  }

  @PatchMapping("/policies/{id}")
  public ObjectNode updatePolicy(@PathVariable UUID id, @RequestBody JsonNode body) {
    return protection.updatePolicy(CurrentUser.userId(), id, body);
  }

  @DeleteMapping("/policies/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePolicy(@PathVariable UUID id) {
    protection.deletePolicy(CurrentUser.userId(), id);
  }

  @GetMapping("/protection")
  public FinProtectionUseCase.ProtectionView protection() {
    return protection.protection(CurrentUser.userId());
  }

  @GetMapping("/estate/{personId}")
  public ObjectNode estate(@PathVariable UUID personId) {
    return protection.estate(CurrentUser.userId(), personId);
  }

  @PutMapping("/estate/{personId}")
  public ObjectNode putEstate(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return protection.putEstate(CurrentUser.userId(), personId, body);
  }

  // ── Money Health and the action plan ───────────────────────────────────────

  @GetMapping("/health")
  public FinHealthUseCase.HealthView health() {
    return health.health(CurrentUser.userId());
  }

  @PostMapping("/health/run")
  public FinHealthUseCase.HealthView runHealth() {
    return health.run(CurrentUser.userId());
  }

  @GetMapping("/actions")
  public List<ObjectNode> actions() {
    return health.actions(CurrentUser.userId());
  }

  public record ActionBody(@NotNull @Size(max = 12) String status, LocalDate snoozedUntil,
                           @Size(max = 200) String reason) {}

  @PatchMapping("/actions/{id}")
  public ObjectNode updateAction(@PathVariable UUID id, @Valid @RequestBody ActionBody body) {
    return health.updateAction(CurrentUser.userId(), id, body.status(), body.snoozedUntil(), body.reason());
  }
}
