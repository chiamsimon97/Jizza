package com.jizza.platform.usecase.finance.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.Edit;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.GoalNeed;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.Line;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.Needs;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.Result;
import com.jizza.platform.usecase.finance.goal.FundingStatusService.Funding;
import com.jizza.platform.usecase.finance.goal.GoalMath;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.health.HouseholdLoader.Loaded;
import com.jizza.platform.usecase.finance.health.MoneyHealthService;
import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.model.Inr;
import com.jizza.platform.usecase.finance.model.KaiConfig;
import com.jizza.platform.usecase.finance.model.PlanConfig;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The plan builder (7.19): reads the budget and the user's changes, works out survival needs and
 * each goal's required SIP, and asks {@link AllocationResolver} for the split. Preview changes
 * nothing; save stores the inputs and writes the allocation lines. Nothing is bought, and no
 * schedule exists before Phase 3: a saved plan is the split Kai will track against.
 */
@Service
public class FinBuilderUseCase {

  static final BigDecimal MAX = new BigDecimal("100000000000");
  static final int MAX_EDITS = 60;

  private final FinanceRecords records;
  private final HouseholdLoader loader;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public FinBuilderUseCase(FinanceRecords records, HouseholdLoader loader, FinScopeResolver scopes,
                           AuditLog audit) {
    this.records = records;
    this.loader = loader;
    this.scopes = scopes;
    this.audit = audit;
  }

  /** What the user typed: amounts, the age and risk chips, and the nodes they set. */
  public record BuilderInput(BigDecimal monthly, BigDecimal lumpsum, String frequency,
                             BigDecimal stepUpPct, Integer topupMonth, BigDecimal topupAmount,
                             Integer age, String riskProfile, List<Edit> edits) {}

  /**
   * @param ageSource dob, you (typed on the chip) or missing
   * @param riskSource assessment, you or default
   */
  public record BuilderView(BuilderInput input, int age, String ageSource, String risk, String riskSource,
                            Result result, Instant savedAt, List<ObjectNode> savedLines) {}

  public BuilderView get(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode budget = budget(owner);
    BuilderInput in = budget == null ? new BuilderInput(BigDecimal.ZERO, BigDecimal.ZERO, "monthly",
        BigDecimal.ZERO, null, null, null, null, List.of()) : input(budget);
    BuilderView v;
    try {
      v = compute(owner, in);
    } catch (ValidationException e) {
      // A change saved earlier no longer fits (the risk profile dropped a part, say): show Kai's split.
      in = new BuilderInput(in.monthly(), in.lumpsum(), in.frequency(), in.stepUpPct(), in.topupMonth(),
          in.topupAmount(), in.age(), in.riskProfile(), List.of());
      v = compute(owner, in);
      List<String> notes = new ArrayList<>(v.result().notes());
      notes.add(0, "Some of your earlier changes no longer fit the plan, so Kai's split is shown. " + e.getMessage());
      Result r = v.result();
      v = new BuilderView(in, v.age(), v.ageSource(), v.risk(), v.riskSource(),
          new Result(r.monthlyIn(), r.lumpsumIn(), r.offTheTop(), r.monthlyInvested(), r.lumpsumInvested(),
              r.age(), r.risk(), r.tree(), r.lines(), r.goalLens(), r.timeline(), notes), null, List.of());
    }
    List<ObjectNode> lines = budget == null ? List.of()
        : records.list("fin_allocation_line", owner, Map.of("budgetId", budget.path("id").asText()));
    Instant saved = budget == null || !budget.hasNonNull("savedAt") ? null
        : java.time.OffsetDateTime.parse(budget.get("savedAt").asText()).toInstant();
    return new BuilderView(v.input(), v.age(), v.ageSource(), v.risk(), v.riskSource(), v.result(), saved, lines);
  }

  public BuilderView preview(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    return compute(owner, parse(body));
  }

  /** Stores the inputs only (a draft). */
  @Transactional
  public BuilderView put(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    BuilderInput in = parse(body);
    compute(owner, in);
    store(owner, in, false);
    audit.record(owner, ActorType.USER, caller, "finance.builder.updated", null, null);
    return get(caller);
  }

  /** Stores the inputs and writes the allocation lines. Nothing is bought. */
  @Transactional
  public BuilderView save(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    BuilderInput in = parse(body);
    BuilderView v = compute(owner, in);
    UUID budgetId = store(owner, in, true);
    records.deleteWhere("fin_allocation_line", owner, Map.of("budgetId", budgetId.toString()));
    for (Line l : v.result().lines()) {
      ObjectNode row = Json.obj().put("budgetId", budgetId.toString()).put("nodeKey", l.key())
          .put("bucket", l.bucket()).put("assetClass", l.assetClass()).put("subCategory", l.subCategory())
          .put("monthlyAmount", l.monthly()).put("lumpsumAmount", l.lumpsum()).put("pct", l.pct())
          .put("source", l.source()).put("sourceLabel", l.sourceLabel());
      records.insert("fin_allocation_line", owner, row);
    }
    audit.record(owner, ActorType.USER, caller, "finance.builder.saved", budgetId.toString(), null);
    return get(caller);
  }

  // ── internals ───────────────────────────────────────────────────────────────

  BuilderView compute(UUID owner, BuilderInput in) {
    Loaded l = loader.load(owner);
    Household h = l.household();
    LocalDate today = h.asOf();
    Person self = h.people().stream().filter(p -> "self".equals(p.kind())).findFirst().orElse(null);
    int age;
    String ageSource;
    if (in.age() != null) {
      age = in.age();
      ageSource = "you";
    } else if (self != null && self.dob() != null) {
      age = self.ageOn(today);
      ageSource = "dob";
    } else {
      age = 35;
      ageSource = "missing";
    }
    String risk;
    String riskSource;
    if (in.riskProfile() != null) {
      risk = in.riskProfile();
      riskSource = "you";
    } else if (self != null && l.latestRisk().containsKey(self.id())) {
      risk = l.latestRisk().get(self.id());
      riskSource = "assessment";
    } else {
      risk = "moderate";
      riskSource = "default";
    }

    Needs needs = needs(h, l.config());
    Map<String, Person> people = new HashMap<>();
    h.people().forEach(p -> people.put(p.id(), p));
    List<GoalNeed> goals = new ArrayList<>();
    for (ObjectNode g : l.goals()) {
      if (!GoalMath.LIVE.contains(g.path("status").asText()) || "emergency".equals(g.path("type").asText())) continue;
      Funding f = GoalMath.funding(g, people, l.latestRisk(), l.selfId(), today, l.plan());
      if (f.requiredSip().signum() <= 0) continue;
      goals.add(new GoalNeed(g.path("id").asText(), g.path("name").asText(), f.bucket(),
          g.path("priorityTier").asText(), LocalDate.parse(g.path("targetDate").asText()), f.requiredSip()));
    }
    BigDecimal monthly = Inr.nz(in.monthly());
    if ("quarterly".equals(in.frequency())) monthly = Inr.rupees(Inr.div(monthly, BigDecimal.valueOf(3)));
    Result r = AllocationResolver.resolve(new AllocationResolver.Input(monthly, in.lumpsum(), age, risk,
        needs, goals, in.edits()), l.plan());
    if ("missing".equals(ageSource)) {
      List<String> notes = new ArrayList<>(r.notes());
      notes.add(0, "Kai does not know your age yet, so it used 35. Add your date of birth, or set the age chip.");
      r = new Result(r.monthlyIn(), r.lumpsumIn(), r.offTheTop(), r.monthlyInvested(), r.lumpsumInvested(),
          r.age(), r.risk(), r.tree(), r.lines(), r.goalLens(), r.timeline(), notes);
    }
    if (!h.knows(Household.SPENDING) || !h.knows(Household.SAVINGS)) {
      List<String> notes = new ArrayList<>(r.notes());
      notes.add("Kai does not know your essential spending or savings yet, so nothing was set aside for the emergency fund.");
      r = new Result(r.monthlyIn(), r.lumpsumIn(), r.offTheTop(), r.monthlyInvested(), r.lumpsumInvested(),
          r.age(), r.risk(), r.tree(), r.lines(), r.goalLens(), r.timeline(), notes);
    }
    return new BuilderView(in, age, ageSource, risk, riskSource, r, null, List.of());
  }

  /** Survival needs from the user's figures: emergency gaps and loans above the high-interest line. */
  static Needs needs(Household h, KaiConfig cfg) {
    var health = MoneyHealthService.run(h, cfg);
    BigDecimal rate = cfg.survival().highInterestRatePct();
    BigDecimal starter = BigDecimal.ZERO;
    BigDecimal rest = BigDecimal.ZERO;
    if (h.knows(Household.SPENDING) && h.knows(Household.SAVINGS)) {
      BigDecimal essential = health.cashFlow().essential();
      BigDecimal liquid = health.liquidEmergency();
      BigDecimal three = essential.multiply(cfg.survival().starterBufferMonths());
      BigDecimal six = essential.multiply(cfg.survival().fullEmergencyMonths());
      starter = Inr.rupees(three.subtract(liquid).max(BigDecimal.ZERO));
      rest = Inr.rupees(six.subtract(liquid).max(BigDecimal.ZERO)).subtract(starter).max(BigDecimal.ZERO);
    }
    BigDecimal debt = BigDecimal.ZERO;
    for (Household.Loan loan : h.loans()) {
      boolean high = Inr.nz(loan.ratePct()).compareTo(rate) > 0;
      if (high) debt = debt.add(Inr.nz(loan.outstanding()));
    }
    return new Needs(starter, rest, Inr.rupees(debt), rate);
  }

  static BuilderInput parse(JsonNode b) {
    if (b == null || !b.isObject()) throw new ValidationException("Send the plan as an object.");
    BigDecimal monthly = money(b, "monthly");
    BigDecimal lumpsum = money(b, "lumpsum");
    String frequency = b.path("frequency").asText("monthly");
    if (!List.of("monthly", "quarterly").contains(frequency)) throw new ValidationException("frequency must be monthly or quarterly.");
    BigDecimal stepUp = b.hasNonNull("stepUpPct") ? b.get("stepUpPct").decimalValue() : BigDecimal.ZERO;
    if (stepUp.signum() < 0 || stepUp.compareTo(BigDecimal.valueOf(100)) > 0) throw new ValidationException("stepUpPct must be between 0 and 100.");
    Integer topupMonth = b.hasNonNull("topupMonth") ? b.get("topupMonth").asInt() : null;
    if (topupMonth != null && (topupMonth < 1 || topupMonth > 12)) throw new ValidationException("topupMonth must be 1 to 12.");
    BigDecimal topup = b.hasNonNull("topupAmount") ? money(b, "topupAmount") : null;
    Integer age = b.hasNonNull("age") ? b.get("age").asInt() : null;
    if (age != null && (age < 0 || age > 110)) throw new ValidationException("age must be between 0 and 110.");
    String risk = b.hasNonNull("riskProfile") ? b.get("riskProfile").asText() : null;
    if (risk != null && !PlanConfig.RISKS.contains(risk)) throw new ValidationException("riskProfile must be conservative, moderate or aggressive.");
    List<Edit> edits = new ArrayList<>();
    JsonNode e = b.path("edits");
    if (!e.isMissingNode() && !e.isNull()) {
      if (!e.isArray()) throw new ValidationException("edits must be a list.");
      if (e.size() > MAX_EDITS) throw new ValidationException("Too many changes.");
      for (JsonNode x : e) {
        if (!x.path("key").isTextual() || x.path("key").asText().length() > 80) throw new ValidationException("Each change needs a node key.");
        edits.add(new Edit(x.get("key").asText(), money(x, "monthly")));
      }
    }
    return new BuilderInput(monthly, lumpsum, frequency, stepUp, topupMonth, topup, age, risk, edits);
  }

  private static BigDecimal money(JsonNode b, String f) {
    JsonNode v = b.get(f);
    if (v == null || v.isNull()) return BigDecimal.ZERO;
    BigDecimal d;
    try {
      d = v.isNumber() ? v.decimalValue() : new BigDecimal(v.asText().replace(",", "").trim());
    } catch (NumberFormatException ex) {
      throw new ValidationException(f + " must be an amount in rupees.");
    }
    if (d.signum() < 0 || d.compareTo(MAX) > 0) throw new ValidationException(f + " must be an amount in rupees.");
    return d;
  }

  private ObjectNode budget(UUID owner) {
    return records.list("fin_investment_budget", owner, Map.of()).stream()
        .filter(b -> !b.hasNonNull("personId")).findFirst().orElse(null);
  }

  static BuilderInput input(ObjectNode b) {
    JsonNode c = b.path("choices");
    List<Edit> edits = new ArrayList<>();
    c.path("edits").forEach(e -> edits.add(new Edit(e.path("key").asText(), e.path("monthly").decimalValue())));
    return new BuilderInput(HouseholdLoader.dec(b, "monthlyAmount"), HouseholdLoader.dec(b, "lumpsum"),
        b.path("frequency").asText("monthly"), HouseholdLoader.dec(b, "stepupPct"),
        b.hasNonNull("topupMonth") ? b.get("topupMonth").asInt() : null, HouseholdLoader.dec(b, "topupAmount"),
        c.hasNonNull("age") ? c.get("age").asInt() : null,
        c.hasNonNull("riskProfile") ? c.get("riskProfile").asText() : null, edits);
  }

  private UUID store(UUID owner, BuilderInput in, boolean saved) {
    ObjectNode choices = Json.obj();
    if (in.age() != null) choices.put("age", in.age());
    if (in.riskProfile() != null) choices.put("riskProfile", in.riskProfile());
    ArrayNode edits = choices.putArray("edits");
    in.edits().forEach(e -> edits.addObject().put("key", e.key()).put("monthly", e.monthly()));
    ObjectNode row = Json.obj().put("monthlyAmount", Inr.nz(in.monthly())).put("lumpsum", Inr.nz(in.lumpsum()))
        .put("frequency", in.frequency()).put("stepupPct", Inr.nz(in.stepUpPct()));
    if (in.topupMonth() != null) row.put("topupMonth", in.topupMonth()); else row.putNull("topupMonth");
    if (in.topupAmount() != null) row.put("topupAmount", in.topupAmount()); else row.putNull("topupAmount");
    row.set("choices", choices);
    if (saved) row.put("savedAt", Instant.now().toString());
    ObjectNode current = budget(owner);
    if (current == null) return UUID.fromString(records.insert("fin_investment_budget", owner, row).path("id").asText());
    UUID id = UUID.fromString(current.path("id").asText());
    records.update("fin_investment_budget", owner, id, row);
    return id;
  }
}
