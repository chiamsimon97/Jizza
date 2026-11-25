package com.jizza.platform.usecase.finance.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.goal.GoalMath;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.health.HouseholdLoader.Loaded;
import com.jizza.platform.usecase.finance.health.MoneyHealthService;
import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Inr;
import com.jizza.platform.usecase.finance.risk.RiskAssessment.Facts;
import com.jizza.platform.usecase.finance.risk.RiskAssessment.Question;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The risk profile for one person (spec 2A.4): the questionnaire, the capacity Kai reads from the
 * user's own figures, and the history of answers. Indicative only.
 */
@Service
public class FinRiskUseCase {

  private final FinanceRecords records;
  private final FinanceStore store;
  private final HouseholdLoader loader;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public FinRiskUseCase(FinanceRecords records, FinanceStore store, HouseholdLoader loader,
                        FinScopeResolver scopes, AuditLog audit) {
    this.records = records;
    this.store = store;
    this.loader = loader;
    this.scopes = scopes;
    this.audit = audit;
  }

  /**
   * @param latest the newest assessment, or null
   * @param capacityNow capacity from today's figures, so the screen can show it before any answer
   */
  public record RiskView(String personId, List<Question> questions, ObjectNode latest,
                         List<RiskAssessment.FactScore> capacityNow, String note) {}

  public static final String NOTE = "Indicative only. This is not the suitability assessment a "
      + "SEBI-registered adviser must perform, and SEBI sets no official mix per profile.";

  public RiskView get(UUID caller, UUID personId) {
    UUID owner = scopes.own(caller).ownerUserId();
    requirePerson(owner, personId);
    List<ObjectNode> rows = records.list("fin_risk_assessment", owner, Map.of("personId", personId.toString()));
    Loaded l = loader.load(owner);
    return new RiskView(personId.toString(), RiskAssessment.QUESTIONS,
        rows.isEmpty() ? null : rows.get(rows.size() - 1), RiskAssessment.capacityFacts(facts(owner, l)), NOTE);
  }

  /** Scores the answers with today's capacity and stores the result. */
  @Transactional
  public ObjectNode assess(UUID caller, UUID personId, JsonNode answers) {
    UUID owner = scopes.own(caller).ownerUserId();
    requirePerson(owner, personId);
    List<Integer> list = new ArrayList<>();
    if (answers == null || !answers.isArray()) throw new ValidationException("answers must be a list of six numbers.");
    for (JsonNode a : answers) {
      if (!a.isIntegralNumber()) throw new ValidationException("Each answer must be 1, 2 or 3.");
      list.add(a.asInt());
    }
    Loaded l = loader.load(owner);
    RiskAssessment.Result r = RiskAssessment.assess(list, facts(owner, l));
    ObjectNode row = Json.obj().put("personId", personId.toString())
        .put("questionnaireVersion", RiskAssessment.VERSION)
        .put("capacityScore", r.capacityScore()).put("toleranceScore", r.toleranceScore())
        .put("capacityProfile", r.capacity()).put("toleranceProfile", r.tolerance())
        .put("profile", r.profile()).put("conflictFlag", r.conflict());
    ObjectNode stored = Json.obj();
    ArrayNode tol = stored.putArray("tolerance");
    list.forEach(tol::add);
    row.set("answers", stored);
    ObjectNode facts = Json.obj();
    facts.set("scores", Json.toNode(r.facts()));
    facts.put("decidedBy", r.decidedBy()).put("explanation", r.explanation());
    row.set("facts", facts);
    ObjectNode saved = records.insert("fin_risk_assessment", owner, row);
    audit.record(owner, ActorType.USER, caller, "finance.risk.assessed", saved.path("id").asText(), null);
    return saved;
  }

  /** The capacity facts from the user's own figures; null where Kai has no data. */
  Facts facts(UUID owner, Loaded l) {
    var health = MoneyHealthService.run(l.household(), l.config());
    int stable = 0;
    boolean any = false;
    for (ObjectNode i : records.list("fin_income_source", owner, Map.of())) {
      if (Inr.nz(HouseholdLoader.dec(i, "takeHomeMonthly")).signum() <= 0) continue;
      any = true;
      if (!"variable".equals(i.path("stability").asText("stable"))) stable++;
    }
    return facts(l.household(), health, l.goals(), any ? stable : null);
  }

  /** The capacity facts from plain records (the fixture tests use this directly). */
  public static Facts facts(Household h, MoneyHealthService.Health health, List<ObjectNode> goals,
                            Integer stableEarners) {
    boolean hasIncome = h.incomes().stream().anyMatch(i -> Inr.nz(i.takeHomeMonthly()).signum() > 0);
    int dependants = (int) h.people().stream().filter(p -> p.dependent() || "child".equals(p.kind())).count();
    var em = health.check(MoneyHealthService.EMERGENCY);
    var debt = health.check(MoneyHealthService.DEBT);
    BigDecimal years = null;
    LocalDate today = h.asOf();
    for (ObjectNode g : goals) {
      if (!GoalMath.LIVE.contains(g.path("status").asText()) || !"important".equals(g.path("priorityTier").asText())) continue;
      long months = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(LocalDate.parse(g.path("targetDate").asText())));
      BigDecimal y = Inr.one(Inr.div(BigDecimal.valueOf(months), Inr.TWELVE));
      if (years == null || y.compareTo(years) < 0) years = y;
    }
    return new Facts(stableEarners, hasIncome, dependants,
        em != null && em.known() ? em.shown() : null,
        debt != null && debt.known() ? debt.shown() : null, years);
  }

  private void requirePerson(UUID owner, UUID personId) {
    if (store.person(owner, personId).isEmpty()) {
      throw new com.jizza.engine.api.error.NotFoundException("Person", personId);
    }
  }
}
