package com.jizza.platform.usecase.finance.cashflow;

import static com.jizza.platform.usecase.finance.model.Inr.rupees;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.cashflow.CashFlowService.CashFlow;
import com.jizza.platform.usecase.finance.cashflow.NetWorthService.NetWorth;
import com.jizza.platform.usecase.finance.config.FinConfigService;
import com.jizza.platform.usecase.finance.debt.DebtPlanner;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.health.MoneyHealthService;
import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Inr;
import com.jizza.platform.usecase.finance.model.KaiConfig;
import com.jizza.platform.usecase.finance.records.FinRecordsUseCase;
import com.jizza.platform.usecase.finance.records.FinTable;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Income, budget and categories, the monthly cash flow with where the surplus goes, and net worth
 * with its monthly snapshots (5.7).
 */
@Service
public class FinCashFlowUseCase {

  private static final int MAX_LINES = 60;

  private final FinanceRecords records;
  private final FinRecordsUseCase forms;
  private final HouseholdLoader loader;
  private final FinConfigService config;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public FinCashFlowUseCase(FinanceRecords records, FinRecordsUseCase forms, HouseholdLoader loader,
                            FinConfigService config, FinScopeResolver scopes, AuditLog audit) {
    this.records = records;
    this.forms = forms;
    this.loader = loader;
    this.config = config;
    this.scopes = scopes;
    this.audit = audit;
  }

  // ── Categories, income and budget ───────────────────────────────────────────

  /** The owner's categories, with Kai's defaults added the first time. */
  public List<ObjectNode> categories(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    ensureCategories(owner);
    return records.list("fin_category", owner, Map.of());
  }

  /** The category with this system key (essentials, lifestyle, investing…), created if needed. */
  public UUID categoryId(UUID owner, String systemKey) {
    ensureCategories(owner);
    return records.list("fin_category", owner, Map.of("systemKey", systemKey)).stream().findFirst()
        .map(r -> UUID.fromString(r.path("id").asText()))
        .orElseThrow(() -> new ValidationException("No category " + systemKey));
  }

  void ensureCategories(UUID owner) {
    List<ObjectNode> existing = records.list("fin_category", owner, Map.of());
    Set<String> keys = new HashSet<>();
    Set<String> names = new HashSet<>();
    existing.forEach(r -> {
      keys.add(r.path("systemKey").asText());
      names.add(r.path("name").asText());
    });
    for (JsonNode d : config.value(owner, "cashflow.default_categories")) {
      String key = d.path("key").asText();
      if (keys.contains(key) || names.contains(d.path("name").asText())) continue;
      try {
        records.insert("fin_category", owner, Json.obj().put("name", d.path("name").asText())
            .put("bucket", d.path("bucket").asText()).put("isEssential", d.path("essential").asBoolean())
            .put("systemKey", key));
      } catch (DataIntegrityViolationException raced) {
        // Added by a parallel request.
      }
    }
  }

  public List<ObjectNode> income(UUID caller) {
    return forms.list(caller, FinTable.INCOME, Map.of());
  }

  /** Replaces the owner's income lines with the list sent. */
  public List<ObjectNode> putIncome(UUID caller, JsonNode lines) {
    UUID owner = scopes.own(caller).ownerUserId();
    List<ObjectNode> clean = cleanList(owner, FinTable.INCOME, lines);
    records.deleteWhere("fin_income_source", owner, Map.of());
    clean.forEach(v -> records.insert("fin_income_source", owner, v));
    audit.record(owner, ActorType.USER, caller, "finance.income.saved", null, null);
    return records.list("fin_income_source", owner, Map.of());
  }

  public List<ObjectNode> budget(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    ensureCategories(owner);
    return records.list("fin_expense_budget", owner, Map.of());
  }

  /** Replaces the owner's budget with the lines sent (one per category). */
  public List<ObjectNode> putBudget(UUID caller, JsonNode lines) {
    UUID owner = scopes.own(caller).ownerUserId();
    ensureCategories(owner);
    List<ObjectNode> clean = cleanList(owner, FinTable.BUDGET, lines);
    Set<String> seen = new HashSet<>();
    for (ObjectNode v : clean) {
      if (!seen.add(v.path("categoryId").asText())) throw new ValidationException("A category appears twice.");
    }
    records.deleteWhere("fin_expense_budget", owner, Map.of());
    clean.forEach(v -> records.insert("fin_expense_budget", owner, v.put("source", "manual")));
    audit.record(owner, ActorType.USER, caller, "finance.budget.saved", null, null);
    return records.list("fin_expense_budget", owner, Map.of());
  }

  private List<ObjectNode> cleanList(UUID owner, FinTable t, JsonNode lines) {
    if (lines == null || !lines.isArray()) throw new ValidationException("Send a list.");
    if (lines.size() > MAX_LINES) throw new ValidationException("Too many lines (" + MAX_LINES + " at most).");
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode line : lines) {
      ObjectNode body = line.isObject() ? ((ObjectNode) line).deepCopy() : null;
      if (body != null) {
        body.remove("id");
        body.remove("source");
      }
      out.add(forms.clean(owner, t, body, true));
    }
    return out;
  }

  // ── Cash flow ───────────────────────────────────────────────────────────────

  public record IncomeLine(String personId, String name, BigDecimal grossMonthly, BigDecimal takeHomeMonthly) {}

  public record SurplusUse(String key, String label, BigDecimal amount) {}

  public record CashFlowView(LocalDate asOf, String month, List<IncomeLine> income,
                             Map<String, BigDecimal> spendByBucket, BigDecimal takeHome,
                             BigDecimal essential, BigDecimal loanPayments,
                             BigDecimal premiumsMonthly, BigDecimal surplus,
                             List<SurplusUse> surplusUse, Map<String, BigDecimal> needsWantsSavePct) {}

  /** The month's cash flow from the budget (imports refine it from Phase 1c). */
  public CashFlowView cashFlow(UUID caller, String month) {
    UUID owner = scopes.own(caller).ownerUserId();
    YearMonth ym = parseMonth(month);
    var loaded = loader.load(owner);
    Household h = loaded.household();
    CashFlow cf = CashFlowService.compute(h);
    List<IncomeLine> income = new ArrayList<>();
    for (var i : cf.income()) {
      String name = h.people().stream().filter(p -> p.id().equals(i.personId())).map(Household.Person::name)
          .findFirst().orElse("");
      income.add(new IncomeLine(i.personId(), name, rupees(i.grossMonthly()), rupees(i.takeHomeMonthly())));
    }
    Map<String, BigDecimal> buckets = new LinkedHashMap<>();
    buckets.put("need", rupees(cf.essential()));
    buckets.put("want", rupees(cf.want()));
    buckets.put("save", rupees(cf.save()));
    Map<String, BigDecimal> pcts = new LinkedHashMap<>();
    if (cf.takeHome().signum() > 0) {
      buckets.forEach((k, v) -> pcts.put(k, Inr.one(Inr.pct(v, cf.takeHome()))));
    }
    return new CashFlowView(h.asOf(), ym.toString(), income, buckets, rupees(cf.takeHome()),
        rupees(cf.essential()), rupees(cf.loanPayments()), rupees(cf.premiumsMonthly()),
        rupees(cf.surplus()), surplusUse(h, cf, loaded.config()), pcts);
  }

  /**
   * Where this month's surplus goes, by the priority ladder (2A.2): the starter buffer takes all
   * of it; then the debt planner's amount; then the rest of the emergency fund, capped at its
   * share of what is left; then goals and investing (split by goal from Phase 1b).
   */
  static List<SurplusUse> surplusUse(Household h, CashFlow cf, KaiConfig cfg) {
    List<SurplusUse> out = new ArrayList<>();
    BigDecimal left = cf.surplus().max(BigDecimal.ZERO);
    if (left.signum() == 0) return out;
    BigDecimal liquid = MoneyHealthService.liquidEmergency(h);
    var s = cfg.survival();
    if (h.knows(Household.SAVINGS) && cf.essential().signum() > 0) {
      BigDecimal starter = cf.essential().multiply(s.starterBufferMonths()).subtract(liquid);
      if (starter.signum() > 0) {
        out.add(new SurplusUse("starter_buffer", "Starter emergency fund", rupees(left)));
        return out;
      }
    }
    if (!h.loans().isEmpty()) {
      var plan = DebtPlanner.plan(h.loans(), left, h.asOf(), cfg, null, BigDecimal.ZERO);
      var first = plan.monthly().isEmpty() ? null : plan.monthly().get(0);
      if (first != null) {
        BigDecimal debt = first.payments().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debt.signum() > 0) {
          out.add(new SurplusUse("debt", "Loans (debt plan)", rupees(debt)));
          left = left.subtract(debt);
        }
      }
    }
    if (left.signum() > 0 && h.knows(Household.SAVINGS) && cf.essential().signum() > 0) {
      BigDecimal gap = cf.essential().multiply(s.fullEmergencyMonths()).subtract(liquid);
      if (gap.signum() > 0) {
        BigDecimal share = rupees(left.multiply(s.emergencyShareOfRestPct()).divide(Inr.HUNDRED));
        BigDecimal amount = share.min(rupees(gap));
        out.add(new SurplusUse("emergency_fund", "Emergency fund to " + s.fullEmergencyMonths().stripTrailingZeros().toPlainString() + " months", amount));
        left = left.subtract(amount);
      }
    }
    if (left.signum() > 0) out.add(new SurplusUse("goals", "Goals and investing", rupees(left)));
    return out;
  }

  static YearMonth parseMonth(String month) {
    if (month == null || month.isBlank()) return YearMonth.from(HouseholdLoader.today());
    try {
      return YearMonth.parse(month);
    } catch (Exception e) {
      throw new ValidationException("month must look like 2026-11.");
    }
  }

  // ── Net worth ───────────────────────────────────────────────────────────────

  public record TrendPoint(LocalDate asOf, BigDecimal assets, BigDecimal liabilities, BigDecimal netWorth) {}

  public record NetWorthView(LocalDate asOf, BigDecimal assets, BigDecimal liabilities,
                             BigDecimal netWorth, BigDecimal pledged,
                             List<NetWorthService.Slice> byClass,
                             List<NetWorthService.Slice> byPerson, List<TrendPoint> trend) {}

  public NetWorthView netWorth(UUID caller, UUID personId) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (personId != null) forms.requirePerson(owner, personId);
    var h = loader.load(owner).household();
    NetWorth nw = NetWorthService.compute(h, personId == null ? null : personId.toString());
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("personId", personId == null ? null : personId.toString());
    List<TrendPoint> trend = records.list("fin_net_worth_snapshot", owner, where).stream()
        .map(r -> new TrendPoint(HouseholdLoader.date(r, "asOf"), HouseholdLoader.dec(r, "assets"),
            HouseholdLoader.dec(r, "liabilities"),
            HouseholdLoader.dec(r, "assets").subtract(HouseholdLoader.dec(r, "liabilities"))))
        .sorted(java.util.Comparator.comparing(TrendPoint::asOf)).toList();
    return new NetWorthView(h.asOf(), nw.assets(), nw.liabilities(), nw.netWorth(), nw.pledged(),
        nw.byClass(), nw.byPerson(), trend);
  }

  /** Writes today's snapshot for the profile and each person (the 1st of the month, imports). */
  public void snapshot(UUID owner) {
    var h = loader.load(owner).household();
    write(owner, null, NetWorthService.compute(h, null), h.asOf());
    for (var p : h.people()) write(owner, p.id(), NetWorthService.compute(h, p.id()), h.asOf());
  }

  private void write(UUID owner, String personId, NetWorth nw, LocalDate asOf) {
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("personId", personId);
    where.put("asOf", asOf.toString());
    records.deleteWhere("fin_net_worth_snapshot", owner, where);
    ObjectNode row = Json.obj().put("asOf", asOf.toString()).put("assets", nw.assets())
        .put("liabilities", nw.liabilities());
    if (personId != null) row.put("personId", personId);
    row.set("breakdown", Json.toNode(Map.of("byClass", nw.byClass())));
    records.insert("fin_net_worth_snapshot", owner, row);
  }
}
