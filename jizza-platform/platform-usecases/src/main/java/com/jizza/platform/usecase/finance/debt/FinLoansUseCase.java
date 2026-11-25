package com.jizza.platform.usecase.finance.debt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.cashflow.CashFlowService;
import com.jizza.platform.usecase.finance.debt.DebtPlanner.Plan;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.model.KaiConfig;
import com.jizza.platform.usecase.finance.records.FinRecordsUseCase;
import com.jizza.platform.usecase.finance.records.FinTable;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loans and borrowings (5.13, 7.20): every loan kind, rates normalised from whatever the user
 * typed, pledged gold or securities with the LTV, payments, money lent to others, and the
 * repayment plan.
 */
@Service
public class FinLoansUseCase {

  private static final int MAX_PLEDGES = 20;

  private final FinanceRecords records;
  private final FinRecordsUseCase forms;
  private final HouseholdLoader loader;
  private final FinScopeResolver scopes;

  public FinLoansUseCase(FinanceRecords records, FinRecordsUseCase forms, HouseholdLoader loader,
                         FinScopeResolver scopes) {
    this.records = records;
    this.forms = forms;
    this.loader = loader;
    this.scopes = scopes;
  }

  public List<ObjectNode> list(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    var loaded = loader.load(owner);
    List<ObjectNode> out = new ArrayList<>();
    for (ObjectNode r : records.list("fin_liability", owner, Map.of())) out.add(view(owner, r, loaded));
    return out;
  }

  public ObjectNode get(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    return view(owner, forms.get(caller, FinTable.LIABILITY, id), loader.load(owner));
  }

  @Transactional
  public ObjectNode create(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode fields = withoutPledges(body);
    JsonNode pledges = body == null ? null : body.get("pledges");
    applyDefaults(fields);
    fillRate(fields, null);
    ObjectNode row = forms.create(caller, FinTable.LIABILITY, fields, null);
    UUID id = UUID.fromString(row.path("id").asText());
    if (pledges != null && !pledges.isNull()) replacePledges(owner, id, pledges);
    return view(owner, records.get("fin_liability", owner, id).orElseThrow(), loader.load(owner));
  }

  @Transactional
  public ObjectNode update(UUID caller, UUID id, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode current = forms.get(caller, FinTable.LIABILITY, id);
    ObjectNode fields = withoutPledges(body);
    fillRate(fields, current);
    forms.update(caller, FinTable.LIABILITY, id, fields);
    JsonNode pledges = body == null ? null : body.get("pledges");
    if (pledges != null) replacePledges(owner, id, pledges);
    return view(owner, records.get("fin_liability", owner, id).orElseThrow(), loader.load(owner));
  }

  public void delete(UUID caller, UUID id) {
    forms.delete(caller, FinTable.LIABILITY, id);
  }

  /**
   * Records a payment (actual or promised). An actual payment with a principal part brings the
   * outstanding amount down by that part.
   */
  @Transactional
  public ObjectNode addPayment(UUID caller, UUID loanId, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode loan = forms.get(caller, FinTable.LIABILITY, loanId);
    ObjectNode payment = forms.create(caller, FinTable.LOAN_PAYMENT, body, Map.of("liabilityId", loanId));
    BigDecimal principal = HouseholdLoader.dec(payment, "principalPart");
    if ("actual".equals(payment.path("kind").asText("actual")) && principal != null && principal.signum() > 0) {
      BigDecimal left = HouseholdLoader.dec(loan, "outstanding").subtract(principal).max(BigDecimal.ZERO);
      ObjectNode change = Json.obj().put("outstanding", left);
      if (left.signum() == 0) change.put("status", "closed");
      records.update("fin_liability", owner, loanId, change);
    }
    return payment;
  }

  public List<ObjectNode> payments(UUID caller, UUID loanId) {
    UUID owner = scopes.own(caller).ownerUserId();
    forms.get(caller, FinTable.LIABILITY, loanId);
    return records.list("fin_loan_payment", owner, Map.of("liabilityId", loanId.toString()));
  }

  /**
   * The repayment plan from this month's surplus.
   *
   * @param method avalanche (highest rate first, the default) or snowball (smallest first)
   * @param extra an extra amount a month to try out
   */
  public record PlanView(LocalDate asOf, BigDecimal monthlySurplus, String method, Plan plan,
                         Map<String, String> labels, boolean figuresNeedVerification) {}

  public PlanView plan(UUID caller, String method, BigDecimal extra) {
    if (method != null && !method.isBlank() && !List.of("avalanche", "snowball").contains(method)) {
      throw new ValidationException("method must be avalanche or snowball.");
    }
    if (extra != null && (extra.signum() < 0 || extra.compareTo(new BigDecimal("100000000")) > 0)) {
      throw new ValidationException("extra must be between 0 and 10 crore.");
    }
    UUID owner = scopes.own(caller).ownerUserId();
    var loaded = loader.load(owner);
    var h = loaded.household();
    BigDecimal surplus = CashFlowService.compute(h).surplus().max(BigDecimal.ZERO);
    String how = method == null || method.isBlank() ? loaded.config().prepay().method() : method;
    Plan plan = DebtPlanner.plan(h.loans(), surplus, h.asOf(), loaded.config(), how, extra);
    Map<String, String> labels = new LinkedHashMap<>();
    h.loans().forEach(l -> labels.put(l.id(), l.label()));
    return new PlanView(h.asOf(), surplus, how, plan, labels,
        loaded.verification().getOrDefault(KaiConfig.GOLD_LTV, false));
  }

  // ── Money lent ──────────────────────────────────────────────────────────────

  public List<ObjectNode> given(UUID caller) {
    return forms.list(caller, FinTable.LOAN_GIVEN, Map.of());
  }

  public ObjectNode addGiven(UUID caller, JsonNode body) {
    return forms.create(caller, FinTable.LOAN_GIVEN, body, null);
  }

  public ObjectNode updateGiven(UUID caller, UUID id, JsonNode body) {
    return forms.update(caller, FinTable.LOAN_GIVEN, id, body);
  }

  public void deleteGiven(UUID caller, UUID id) {
    forms.delete(caller, FinTable.LOAN_GIVEN, id);
  }

  // ── internals ───────────────────────────────────────────────────────────────

  private ObjectNode view(UUID owner, ObjectNode row, HouseholdLoader.Loaded loaded) {
    ObjectNode out = row.deepCopy();
    String id = row.path("id").asText();
    List<ObjectNode> pledges = records.list("fin_pledge", owner, Map.of("liabilityId", id));
    out.set("pledges", Json.toNode(pledges));
    out.put("label", HouseholdLoader.label(row));
    out.put("sharedLabel", row.path("isPrivate").asBoolean() ? "Family / friend" : HouseholdLoader.label(row));
    var loan = loaded.household().loans().stream().filter(l -> l.id().equals(id)).findFirst();
    if (loan.isPresent() && ("GOLD_LOAN".equals(row.path("kind").asText()) || !pledges.isEmpty())) {
      var g = DebtPlanner.goldLoan(loan.get(), loaded.household().asOf(), loaded.config());
      ObjectNode ltv = Json.obj();
      ltv.set("detail", Json.toNode(g));
      ltv.put("pct", g.ltvPct());
      ltv.put("capPct", g.capPct());
      ltv.put("warn", g.warn());
      ltv.put("needsVerification", loaded.verification().getOrDefault(KaiConfig.GOLD_LTV, true));
      out.set("ltv", ltv);
    } else {
      out.putNull("ltv");
    }
    return out;
  }

  private static ObjectNode withoutPledges(JsonNode body) {
    if (body == null || !body.isObject()) throw new ValidationException("Send the loan as an object.");
    ObjectNode copy = ((ObjectNode) body).deepCopy();
    copy.remove("pledges");
    return copy;
  }

  /** Sensible defaults for a new loan by kind (the form can change any of them). */
  static void applyDefaults(ObjectNode f) {
    String kind = f.path("kind").asText("");
    switch (kind) {
      case "CREDIT_CARD" -> {
        putIfAbsent(f, "repaymentMode", "revolving");
        putIfAbsent(f, "lenderType", "card_issuer");
      }
      case "GOLD_LOAN" -> {
        putIfAbsent(f, "repaymentMode", "bullet");
        putIfAbsent(f, "collateralType", "gold");
      }
      case "FAMILY_FRIEND" -> {
        putIfAbsent(f, "repaymentMode", "flexible");
        putIfAbsent(f, "lenderType", "family");
        if (!f.has("isPrivate")) f.put("isPrivate", true);
      }
      case "MONEYLENDER" -> {
        putIfAbsent(f, "repaymentMode", "interest_only");
        putIfAbsent(f, "lenderType", "moneylender");
        putIfAbsent(f, "rateType", "informal");
      }
      case "OVERDRAFT" -> putIfAbsent(f, "repaymentMode", "flexible");
      case "CONSUMER_EMI" -> putIfAbsent(f, "rateType", "flat");
      case "LOAN_AGAINST_SECURITIES" -> putIfAbsent(f, "collateralType", "securities");
      case "LOAN_AGAINST_FD" -> putIfAbsent(f, "collateralType", "fd");
      case "LOAN_AGAINST_INSURANCE" -> putIfAbsent(f, "collateralType", "policy");
      case "HOME" -> putIfAbsent(f, "collateralType", "property");
      case "VEHICLE" -> putIfAbsent(f, "collateralType", "vehicle");
      default -> { }
    }
  }

  private static void putIfAbsent(ObjectNode f, String field, String value) {
    if (!f.hasNonNull(field)) f.put(field, value);
  }

  /** Works out the effective rate from the rate as typed, unless the form set it itself. */
  static void fillRate(ObjectNode f, ObjectNode current) {
    if (f.hasNonNull("effectiveAnnualRate")) return;
    boolean touched = f.has("rateInput") || f.has("rateType") || f.has("startDate")
        || f.has("maturityOrPromisedDate");
    if (current != null && !touched) return;
    String input = text(f, current, "rateInput");
    if (input == null) return;
    String type = text(f, current, "rateType");
    LocalDate start = date(f, current, "startDate");
    LocalDate end = date(f, current, "maturityOrPromisedDate");
    Integer months = start != null && end != null && end.isAfter(start)
        ? (int) ChronoUnit.MONTHS.between(start, end) : null;
    BigDecimal rate = RateNormaliser.effectiveAnnualPct(input, type, months);
    if (rate == null) throw new ValidationException("Could not read a rate from \"" + input + "\". Try \"14%\" or \"2 per 100 a month\".");
    if (rate.compareTo(BigDecimal.valueOf(1000)) > 0) throw new ValidationException("That rate is above 1,000% a year.");
    f.put("effectiveAnnualRate", rate);
  }

  private static String text(ObjectNode f, ObjectNode current, String field) {
    JsonNode v = f.has(field) ? f.get(field) : current == null ? null : current.get(field);
    return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
  }

  private static LocalDate date(ObjectNode f, ObjectNode current, String field) {
    String s = text(f, current, field);
    try {
      return s == null ? null : LocalDate.parse(s);
    } catch (Exception e) {
      return null;
    }
  }

  private void replacePledges(UUID owner, UUID loanId, JsonNode pledges) {
    if (!pledges.isArray()) throw new ValidationException("pledges must be a list.");
    if (pledges.size() > MAX_PLEDGES) throw new ValidationException("Too many pledges.");
    List<ObjectNode> clean = new ArrayList<>();
    for (JsonNode p : pledges) {
      ObjectNode body = p.isObject() ? ((ObjectNode) p).deepCopy() : null;
      if (body != null) {
        body.remove("id");
        body.remove("liabilityId");
        body.remove("ltvCapPct");
        body.remove("ltvNowPct");
      }
      clean.add(forms.clean(owner, FinTable.PLEDGE, body, true));
    }
    for (ObjectNode old : records.list("fin_pledge", owner, Map.of("liabilityId", loanId.toString()))) {
      if (old.hasNonNull("assetId")) {
        records.update("fin_asset", owner, UUID.fromString(old.get("assetId").asText()),
            Json.obj().put("pledged", false));
      }
    }
    records.deleteWhere("fin_pledge", owner, Map.of("liabilityId", loanId.toString()));
    for (ObjectNode p : clean) {
      p.put("liabilityId", loanId.toString());
      records.insert("fin_pledge", owner, p);
      if (p.hasNonNull("assetId")) {
        records.update("fin_asset", owner, UUID.fromString(p.get("assetId").asText()),
            Json.obj().put("pledged", true)).orElseThrow(() -> new NotFoundException("Asset", p.get("assetId").asText()));
      }
    }
  }
}
