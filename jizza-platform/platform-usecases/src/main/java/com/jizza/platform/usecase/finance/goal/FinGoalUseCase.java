package com.jizza.platform.usecase.finance.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.cashflow.CashFlowService;
import com.jizza.platform.usecase.finance.goal.FundingStatusService.Funding;
import com.jizza.platform.usecase.finance.goal.GoalMath.RiskUsed;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.health.HouseholdLoader.Loaded;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.model.Inr;
import com.jizza.platform.usecase.finance.model.PlanConfig;
import com.jizza.platform.usecase.finance.records.FinRecordsUseCase;
import com.jizza.platform.usecase.finance.records.FinTable;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goals (spec 2A.5, 5.6; screens 7.2, 7.3, 7.26): the goal library with smart defaults, and a
 * goal's funding status and required SIP. The caller's own data only, through the scope resolver.
 *
 * <p>Until holdings are earmarked (Phase 2) and SIPs are schedules (Phase 3), a goal keeps what is
 * already saved for it and what goes in each month; its funding is worked out from those. Creating
 * a goal buys nothing: a new goal is in Observe mode.
 */
@Service
public class FinGoalUseCase {

  private static final int MAX_GOALS = 50;

  private final FinanceRecords records;
  private final FinRecordsUseCase forms;
  private final HouseholdLoader loader;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public FinGoalUseCase(FinanceRecords records, FinRecordsUseCase forms, HouseholdLoader loader,
                        FinScopeResolver scopes, AuditLog audit) {
    this.records = records;
    this.forms = forms;
    this.loader = loader;
    this.scopes = scopes;
    this.audit = audit;
  }

  /**
   * A library card with the values the wizard pre-fills. {@code targetAmountToday} is null when
   * the amount needs the user's own figure (a fee, a price); {@code hint} says how Kai works it out.
   */
  public record LibraryCard(String type, String name, String priority, String inflationKey,
                            BigDecimal inflationPct, BigDecimal targetAmountToday, LocalDate targetDate,
                            String bucket, String ownerPersonId, String hint, JsonNode defaults) {}

  /**
   * A goal with its funding. {@code riskSource} is goal, self, person or default.
   * {@code moveNote} is "Moves to Mid in 14 months" while a bucket change is ahead.
   */
  public record GoalView(ObjectNode goal, String ownerName, Funding funding, String risk,
                         String riskSource, String moveNote, BigDecimal expectedReturnPct) {}

  public List<LibraryCard> library(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    Loaded l = loader.load(owner);
    LocalDate today = l.household().asOf();
    var cf = CashFlowService.compute(l.household());
    Person self = l.household().people().stream().filter(p -> "self".equals(p.kind())).findFirst().orElse(null);
    Person child = l.household().people().stream().filter(p -> "child".equals(p.kind()) && p.dob() != null)
        .min(Comparator.comparing(Person::dob)).orElse(null);
    Integer age = self == null ? null : self.ageOn(today);
    List<LibraryCard> out = new ArrayList<>();
    for (JsonNode c : l.plan().library()) {
      String type = c.path("type").asText();
      String inflation = c.path("inflation").asText("cpi");
      LocalDate date = today.plusYears(c.path("years").asInt(5));
      BigDecimal amount = null;
      String hint;
      String ownerId = l.selfId();
      switch (c.path("rule").asText("amount")) {
        case "essentialMonths" -> {
          int months = c.path("months").asInt(6);
          hint = months + " × essential monthly spending";
          if (cf.essential().signum() > 0) {
            amount = Inr.rupees(cf.essential().multiply(BigDecimal.valueOf(months)));
            hint += " of " + Inr.group(cf.essential());
          }
        }
        case "yearsOfExpenses" -> {
          int retireAge = c.path("retireAge").asInt(60);
          int multiple = retireAge < c.path("earlyBeforeAge").asInt(55) ? c.path("earlyMultiple").asInt(25)
              : c.path("multiple").asInt(20);
          BigDecimal yearly = cf.essential().add(cf.want()).multiply(Inr.TWELVE);
          hint = multiple + " × yearly spending at retirement, in today's ₹ (" + c.path("earlyMultiple").asInt(25)
              + " × if you retire before " + c.path("earlyBeforeAge").asInt(55) + ")";
          if (yearly.signum() > 0) amount = Inr.rupees(yearly.multiply(BigDecimal.valueOf(multiple)));
          if (self != null && self.dob() != null) date = self.dob().plusYears(retireAge);
        }
        case "feeTimesYears" -> {
          hint = "Today's yearly fee × " + c.path("courseYears").asInt(4) + " years of the course";
          if (child != null) {
            date = child.dob().plusYears(c.path("atChildAge").asInt(18));
            ownerId = child.id();
          }
        }
        case "shareOfPrice" -> hint = c.path("downPaymentPct").asInt(20) + "% of the price, plus registration";
        case "priceMinusLoan" -> hint = "The price, minus the loan you plan to take";
        default -> hint = "Your own amount";
      }
      if (!date.isAfter(today)) date = today.plusYears(1);
      long months = java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(today), java.time.YearMonth.from(date));
      out.add(new LibraryCard(type, c.path("name").asText(type), c.path("priority").asText("aspirational"),
          inflation, l.plan().inflationPct(inflation), amount, date, l.plan().bucketFor(months), ownerId, hint,
          c));
    }
    return out;
  }

  public List<GoalView> list(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    Loaded l = loader.load(owner);
    List<GoalView> out = new ArrayList<>();
    for (ObjectNode g : records.list("fin_plan", owner, Map.of())) out.add(view(g, l));
    return out;
  }

  public GoalView get(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode g = records.get("fin_plan", owner, id).orElseThrow(() -> new NotFoundException("Goal", id));
    return view(g, loader.load(owner));
  }

  /** Adds a goal; fields left out come from the library card for its type. */
  @Transactional
  public GoalView create(UUID caller, JsonNode body, String via) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (body == null || !body.isObject()) throw new ValidationException("Send the goal as an object.");
    if (records.list("fin_plan", owner, Map.of()).size() >= MAX_GOALS) {
      throw new ValidationException("You have " + MAX_GOALS + " goals already. Archive one first.");
    }
    ObjectNode b = ((ObjectNode) body).deepCopy();
    String type = b.path("type").asText("");
    Loaded l = loader.load(owner);
    LibraryCard card = library(caller).stream().filter(c -> c.type().equals(type)).findFirst()
        .orElseThrow(() -> new ValidationException("type must be one of the goal library's goals."));
    if (!b.hasNonNull("name")) b.put("name", card.name());
    if (!b.hasNonNull("targetDate")) b.put("targetDate", card.targetDate().toString());
    if (!b.hasNonNull("targetAmountToday") && card.targetAmountToday() != null) b.put("targetAmountToday", card.targetAmountToday());
    if (!b.hasNonNull("inflationKey")) b.put("inflationKey", card.inflationKey());
    if (!b.hasNonNull("priorityTier")) b.put("priorityTier", card.priority());
    if (!b.hasNonNull("ownerPersonId")) b.put("ownerPersonId", card.ownerPersonId());
    ObjectNode v = forms.clean(owner, FinTable.GOAL, b, true);
    checkDate(v, l.household().asOf());
    if ("emergency".equals(type) && records.list("fin_plan", owner, Map.of("type", "emergency")).stream()
        .anyMatch(g -> GoalMath.LIVE.contains(g.path("status").asText()) || "draft".equals(g.path("status").asText()))) {
      throw new ConflictException("You already have an emergency fund goal. Edit that one instead.");
    }
    v.put("createdVia", via == null ? "wizard" : via);
    ObjectNode row = records.insert("fin_plan", owner, v);
    audit.record(owner, ActorType.USER, caller, "finance.goal.added", row.path("id").asText(), null);
    return view(row, loader.load(owner));
  }

  @Transactional
  public GoalView update(UUID caller, UUID id, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode current = records.get("fin_plan", owner, id).orElseThrow(() -> new NotFoundException("Goal", id));
    ObjectNode v = forms.clean(owner, FinTable.GOAL, body, false);
    if (v.has("type") && !v.path("type").asText().equals(current.path("type").asText())) {
      throw new ValidationException("A goal's type cannot change. Add a new goal instead.");
    }
    if (v.has("targetDate")) checkDate(v, HouseholdLoader.today());
    if (v.has("status") && "achieved".equals(v.path("status").asText())) v.put("achievedAt", Instant.now().toString());
    ObjectNode row = v.isEmpty() ? current
        : records.update("fin_plan", owner, id, v).orElseThrow(() -> new NotFoundException("Goal", id));
    audit.record(owner, ActorType.USER, caller, "finance.goal.updated", id.toString(), null);
    return view(row, loader.load(owner));
  }

  @Transactional
  public GoalView achieve(UUID caller, UUID id) {
    return update(caller, id, Json.obj().put("status", "achieved"));
  }

  /** Removes goals Quick start made, when its goals question is answered again. */
  @Transactional
  public void removeQuickStartGoals(UUID owner, List<String> ids) {
    for (String id : ids) {
      records.get("fin_plan", owner, UUID.fromString(id))
          .filter(g -> "quick_start".equals(g.path("createdVia").asText()))
          .ifPresent(g -> records.delete("fin_plan", owner, UUID.fromString(id)));
    }
  }

  GoalView view(ObjectNode g, Loaded l) {
    Map<String, Person> people = new HashMap<>();
    l.household().people().forEach(p -> people.put(p.id(), p));
    String ownerId = g.path("ownerPersonId").asText(l.selfId());
    Person owner = people.get(ownerId);
    RiskUsed risk = GoalMath.riskFor(g, l.latestRisk(), l.selfId());
    Funding f = GoalMath.funding(g, people, l.latestRisk(), l.selfId(), l.household().asOf(), l.plan());
    String move = null;
    if (f.nextBucket() != null && f.monthsToNextBucket() != null && f.monthsToNextBucket() > 0) {
      move = "Moves to " + (f.nextBucket().equals("mid") ? "Mid" : "Short") + " in " + f.monthsToNextBucket()
          + (f.monthsToNextBucket() == 1 ? " month" : " months");
    }
    return new GoalView(g, owner == null ? null : owner.name(), f, risk.profile(), risk.source(), move,
        f.blendedReturnNow());
  }

  private static void checkDate(ObjectNode v, LocalDate today) {
    if (v.hasNonNull("targetDate") && !LocalDate.parse(v.get("targetDate").asText()).isAfter(today)) {
      throw new ValidationException("The target date must be after today.");
    }
  }

  /** The plan config in force for a caller, for the builder. */
  public PlanConfig planConfig(UUID owner) {
    return loader.load(owner).plan();
  }
}
