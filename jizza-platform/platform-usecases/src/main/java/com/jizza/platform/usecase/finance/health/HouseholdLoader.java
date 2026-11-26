package com.jizza.platform.usecase.finance.health;

import static com.jizza.platform.usecase.finance.model.Inr.HUNDRED;
import static com.jizza.platform.usecase.finance.model.Inr.div;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.platform.usecase.finance.config.FinConfigService;
import com.jizza.platform.usecase.finance.config.FinConfigService.ConfigItem;
import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Household.AccountInfo;
import com.jizza.platform.usecase.finance.model.Household.Asset;
import com.jizza.platform.usecase.finance.model.Household.BudgetLine;
import com.jizza.platform.usecase.finance.model.Household.Income;
import com.jizza.platform.usecase.finance.model.Household.Loan;
import com.jizza.platform.usecase.finance.model.Household.LoanGiven;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.model.Household.Pledge;
import com.jizza.platform.usecase.finance.model.Household.Policy;
import com.jizza.platform.usecase.finance.goal.GoalMath;
import com.jizza.platform.usecase.finance.model.KaiConfig;
import com.jizza.platform.usecase.finance.model.PlanConfig;
import com.jizza.platform.usecase.finance.profile.FinProfileUseCase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Reads one owner's Phase 1a rows into a {@link Household}, the plain record every engine takes,
 * with the configuration in force for them. The caller has already passed the scope resolver.
 */
@Service
public class HouseholdLoader {

  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

  /** Quick start steps 1 to 6 and the area each one answers. */
  public static final Map<String, String> STEP_AREAS = Map.of(
      "income", Household.INCOME, "spending", Household.SPENDING, "loans", Household.LOANS,
      "insurance", Household.INSURANCE, "savings", Household.SAVINGS);

  private final FinanceRecords records;
  private final FinanceStore store;
  private final FinConfigService config;
  private final com.jizza.platform.usecase.finance.ledger.HoldingsReader holdings;

  public HouseholdLoader(FinanceRecords records, FinanceStore store, FinConfigService config,
                         com.jizza.platform.usecase.finance.ledger.HoldingsReader holdings) {
    this.records = records;
    this.store = store;
    this.config = config;
    this.holdings = holdings;
  }

  /**
   * @param verification for each configuration key the engines read, whether it still needs
   *     verification (a regulatory figure nobody has confirmed for this financial year)
   */
  public record Loaded(Household household, KaiConfig config, Map<String, JsonNode> configValues,
                       Map<String, Boolean> verification, PlanConfig plan, List<ObjectNode> goals,
                       Map<String, String> latestRisk, String selfId, Map<String, BigDecimal> earmarked) {

    /**
     * The goal as the engines see it: once holdings are earmarked to it (Phase 2), what it is
     * worth is their value, not the figure typed in the wizard.
     */
    public ObjectNode withEarmarks(ObjectNode goal) {
      BigDecimal v = earmarked == null ? null : earmarked.get(goal.path("id").asText());
      if (v == null) return goal;
      ObjectNode g = goal.deepCopy();
      g.put("typedValue", goal.path("currentValue").decimalValue());
      g.put("currentValue", v.setScale(0, java.math.RoundingMode.HALF_UP));
      g.put("earmarkedValue", v);
      return g;
    }
  }

  /** The system's own goal that holds whatever no goal claims; never shown as a goal. */
  public static boolean isUnassigned(JsonNode goal) {
    return "unassigned".equals(goal.path("type").asText());
  }

  public static LocalDate today() {
    return LocalDate.now(ZONE);
  }

  public Loaded load(UUID owner) {
    return load(owner, today());
  }

  public Loaded load(UUID owner, LocalDate asOf) {
    var profile = store.ensureProfile(owner, FinProfileUseCase.SELF_NAME);
    List<Person> people = store.people(owner).stream().filter(p -> "active".equals(p.status()))
        .map(p -> new Person(p.id().toString(), p.displayName(), p.kind(), p.dob(), p.earning(),
            p.dependent()))
        .toList();
    Set<String> active = people.stream().map(Person::id).collect(Collectors.toSet());

    List<Income> incomes = records.list("fin_income_source", owner, Map.of()).stream()
        .filter(r -> active.contains(r.path("personId").asText()))
        .map(r -> new Income(r.path("personId").asText(), dec(r, "grossMonthly"), dec(r, "takeHomeMonthly")))
        .toList();

    Map<String, ObjectNode> categories = byId(records.list("fin_category", owner, Map.of()));
    List<BudgetLine> budget = new ArrayList<>();
    for (ObjectNode b : records.list("fin_expense_budget", owner, Map.of())) {
      ObjectNode c = categories.get(b.path("categoryId").asText());
      if (c == null) continue;
      budget.add(new BudgetLine(c.path("name").asText(), c.path("bucket").asText(), dec(b, "monthlyAmount")));
    }

    Map<String, List<Pledge>> pledges = new HashMap<>();
    for (ObjectNode p : records.list("fin_pledge", owner, Map.of())) {
      pledges.computeIfAbsent(p.path("liabilityId").asText(), k -> new ArrayList<>())
          .add(new Pledge(p.path("assetKind").asText(), dec(p, "grams"),
              p.hasNonNull("purityKarat") ? p.get("purityKarat").asInt() : null,
              dec(p, "valuation"), date(p, "valuedOn")));
    }
    List<Loan> loans = records.list("fin_liability", owner, Map.of("status", "active")).stream()
        .filter(r -> active.contains(r.path("personId").asText()))
        .map(r -> loan(r, pledges.get(r.path("id").asText()))).toList();

    List<Policy> policies = records.list("fin_insurance_policy", owner, Map.of()).stream()
        .filter(r -> active.contains(r.path("personId").asText()))
        .filter(r -> date(r, "policyEndDate") == null || !date(r, "policyEndDate").isBefore(asOf))
        .map(r -> {
          List<String> covered = new ArrayList<>();
          r.path("coveredPersonIds").forEach(n -> covered.add(n.asText()));
          return new Policy(r.path("id").asText(), r.path("personId").asText(), r.path("kind").asText(),
              dec(r, "sumAssured"), dec(r, "premium"), r.path("premiumFrequency").asText("yearly"),
              r.path("employerProvided").asBoolean(), covered, date(r, "renewalDate"));
        }).toList();

    Map<String, ObjectNode> accounts = byId(records.list("fin_account", owner, Map.of()));
    List<Asset> assets = new ArrayList<>();
    for (ObjectNode r : records.list("fin_asset", owner, Map.of())) {
      if (!active.contains(r.path("personId").asText())) continue;
      ObjectNode account = r.hasNonNull("accountId") ? accounts.get(r.path("accountId").asText()) : null;
      boolean closed = account != null && "closed".equals(account.path("status").asText());
      BigDecimal value = share(dec(r, "value"), account);
      assets.add(new Asset(r.path("id").asText(), r.path("personId").asText(), r.path("kind").asText(),
          r.path("assetClass").asText("other"), value,
          r.path("isEmergency").asBoolean() || (account != null && account.path("isEmergency").asBoolean()),
          r.path("pledged").asBoolean(),
          !closed && (account == null || account.path("countInNetWorth").asBoolean(true))));
    }
    // Phase 2: shares, funds and the rest held in lots, at their latest dated price.
    var positions = holdings.positions(owner, asOf);
    for (var p : positions) {
      if (!active.contains(p.personId()) || p.value() == null) continue;
      ObjectNode account = accounts.get(p.accountId());
      boolean closed = account != null && "closed".equals(account.path("status").asText());
      assets.add(new Asset("holding:" + p.key(), p.personId(), "holding",
          p.instrument() == null ? "other" : p.instrument().path("assetClass").asText("other"), share(p.value(), account),
          account != null && account.path("isEmergency").asBoolean(), false,
          !closed && (account == null || account.path("countInNetWorth").asBoolean(true))));
    }
    Map<String, BigDecimal> earmarked = holdings.goalValues(owner, positions);

    List<LoanGiven> given = records.list("fin_loan_given", owner, Map.of()).stream()
        .filter(r -> active.contains(r.path("personId").asText()))
        .map(r -> new LoanGiven(r.path("id").asText(), r.path("personId").asText(), dec(r, "amount"),
            r.path("status").asText())).toList();

    List<AccountInfo> accountInfo = accounts.values().stream()
        .filter(a -> "active".equals(a.path("status").asText()))
        .map(a -> new AccountInfo(a.path("id").asText(), a.path("personId").asText(),
            a.path("nomineeStatus").asText("unknown"))).toList();

    String self = people.stream().filter(p -> "self".equals(p.kind())).map(Person::id).findFirst().orElse("");
    boolean will = records.list("fin_estate_record", owner, Map.of()).stream()
        .anyMatch(r -> self.equals(r.path("personId").asText()) && !"none".equals(r.path("willStatus").asText()));

    Set<String> known = new HashSet<>();
    JsonNode completed = profile.settings().path("quickStart").path("completed");
    completed.forEach(n -> {
      String area = STEP_AREAS.get(n.asText());
      if (area != null) known.add(area);
    });
    if (!incomes.isEmpty()) known.add(Household.INCOME);
    if (!budget.isEmpty()) known.add(Household.SPENDING);
    if (!loans.isEmpty()) known.add(Household.LOANS);
    if (!policies.isEmpty()) known.add(Household.INSURANCE);
    if (!assets.isEmpty()) known.add(Household.SAVINGS);

    Map<String, JsonNode> values = new HashMap<>();
    Map<String, JsonNode> planValues = new HashMap<>();
    Map<String, Boolean> verification = new HashMap<>();
    for (ConfigItem item : config.list(owner)) {
      if (KaiConfig.KEYS.contains(item.key())) {
        values.put(item.key(), item.value());
        verification.put(item.key(), item.needsVerification());
      }
      if (PlanConfig.KEYS.contains(item.key())) planValues.put(item.key(), item.value());
    }
    PlanConfig plan = PlanConfig.from(planValues);

    // Phase 1b: the Goal funding check is computed from the goals, not given.
    List<ObjectNode> goals = records.list("fin_plan", owner, Map.of()).stream()
        .filter(g -> !isUnassigned(g)).map(g -> {
          BigDecimal v = earmarked.get(g.path("id").asText());
          if (v == null) return g;
          ObjectNode c = g.deepCopy();
          c.put("currentValue", v.setScale(0, java.math.RoundingMode.HALF_UP));
          return c;
        }).toList();
    Map<String, String> latestRisk = GoalMath.latestRisk(records.list("fin_risk_assessment", owner, Map.of()));
    Map<String, Person> byId = new HashMap<>();
    people.forEach(p -> byId.put(p.id(), p));
    List<ObjectNode> ownGoals = goals.stream()
        .filter(g -> active.contains(g.path("ownerPersonId").asText(self))).toList();
    BigDecimal funded = GoalMath.importantFundedPct(ownGoals, byId, latestRisk, self, asOf, plan);

    Household household = new Household(asOf, people, incomes, budget, loans, policies,
        List.copyOf(assets), given, accountInfo, will, funded, Set.copyOf(known));
    return new Loaded(household, KaiConfig.from(values), values, verification, plan, ownGoals,
        latestRisk, self, earmarked);
  }

  /** The loan as the engines see it: the owner's share, the normalised rate. */
  static Loan loan(ObjectNode r, List<Pledge> pledges) {
    BigDecimal outstanding = share(dec(r, "outstanding"), r);
    BigDecimal payment = dec(r, "emiOrInterest");
    if (payment != null && r.path("isJoint").asBoolean()) payment = share(payment, r);
    return new Loan(r.path("id").asText(), r.path("personId").asText(), r.path("kind").asText(),
        label(r), outstanding, dec(r, "effectiveAnnualRate"), r.path("repaymentMode").asText("emi"),
        payment, date(r, "startDate"), date(r, "maturityOrPromisedDate"), dec(r, "principalTaken"),
        r.path("isPrivate").asBoolean(), pledges);
  }

  /** What the owner calls the loan: the lender's name, else its kind in words. */
  public static String label(JsonNode r) {
    String name = r.path("lenderName").asText("");
    if (!name.isBlank()) return name;
    String kind = r.path("kind").asText("OTHER");
    return switch (kind) {
      case "CREDIT_CARD" -> "Credit card";
      case "CONSUMER_EMI" -> "Consumer EMI";
      case "FAMILY_FRIEND" -> "Family / friend";
      case "LOAN_AGAINST_SECURITIES" -> "Loan against securities";
      case "LOAN_AGAINST_FD" -> "Loan against FD";
      case "LOAN_AGAINST_INSURANCE" -> "Loan against insurance";
      case "PPF_LOAN" -> "PPF loan";
      case "CHIT_PRIZE" -> "Chit (after the prize)";
      default -> {
        String w = kind.toLowerCase(Locale.ROOT).replace('_', ' ');
        yield Character.toUpperCase(w.charAt(0)) + w.substring(1) + (w.endsWith("loan") ? "" : " loan");
      }
    };
  }

  /** A joint item counts at the owner's share. */
  static BigDecimal share(BigDecimal value, JsonNode row) {
    if (value == null || row == null || !row.path("isJoint").asBoolean()) return value;
    BigDecimal pct = row.hasNonNull("ownerSharePct") ? row.get("ownerSharePct").decimalValue() : HUNDRED;
    return div(value.multiply(pct), HUNDRED);
  }

  static Map<String, ObjectNode> byId(List<ObjectNode> rows) {
    Map<String, ObjectNode> out = new HashMap<>();
    rows.forEach(r -> out.put(r.path("id").asText(), r));
    return out;
  }

  public static BigDecimal dec(JsonNode r, String field) {
    JsonNode v = r.get(field);
    return v == null || v.isNull() ? null : v.decimalValue();
  }

  public static LocalDate date(JsonNode r, String field) {
    JsonNode v = r.get(field);
    return v == null || v.isNull() ? null : LocalDate.parse(v.asText());
  }
}
