package com.jizza.platform.usecase.finance.quickstart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.finance.cashflow.FinCashFlowUseCase;
import com.jizza.platform.usecase.finance.debt.FinLoansUseCase;
import com.jizza.platform.usecase.finance.goal.FinGoalUseCase;
import com.jizza.platform.usecase.finance.profile.FinPersonUseCase;
import com.jizza.platform.usecase.finance.profile.FinProfileUseCase;
import com.jizza.platform.usecase.finance.records.FinRecordsUseCase;
import com.jizza.platform.usecase.finance.records.FinTable;
import com.jizza.platform.usecase.finance.risk.FinRiskUseCase;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick start (7.13), steps 1 to 8 (goals and risk from Phase 1b): each answer is saved as soon as it is sent, so the user can
 * stop and come back. Answering a step again replaces what that step wrote the first time; rows
 * added elsewhere are left alone. Every step can be skipped: its checks then show "unknown".
 *
 * <p>The answers live in the profile's settings ({@code quickStart}); the records they create are
 * ordinary rows (people, income, budget, loans, policies, assets).
 */
@Service
public class QuickStartUseCase {

  public static final List<String> STEPS =
      List.of("people", "income", "spending", "loans", "insurance", "savings", "goals", "risk");
  /** Quick start offers up to three goals from the library (7.13 step 7). */
  public static final int MAX_GOALS = 3;
  private static final int MAX_ITEMS = 10;
  private static final int MAX_SAVINGS = 12;

  private final FinanceStore store;
  private final FinanceRecords records;
  private final FinRecordsUseCase forms;
  private final FinPersonUseCase people;
  private final FinCashFlowUseCase cashFlow;
  private final FinLoansUseCase loans;
  private final FinGoalUseCase goals;
  private final FinRiskUseCase risk;
  private final FinScopeResolver scopes;
  private final AuditLog audit;
  private final VitaRecords vita;

  public QuickStartUseCase(FinanceStore store, FinanceRecords records, FinRecordsUseCase forms,
                           FinPersonUseCase people, FinCashFlowUseCase cashFlow,
                           FinLoansUseCase loans, FinGoalUseCase goals, FinRiskUseCase risk,
                           FinScopeResolver scopes, AuditLog audit, VitaRecords vita) {
    this.vita = vita;
    this.store = store;
    this.records = records;
    this.forms = forms;
    this.people = people;
    this.cashFlow = cashFlow;
    this.loans = loans;
    this.goals = goals;
    this.risk = risk;
    this.scopes = scopes;
    this.audit = audit;
  }

  public record Progress(List<String> completed, List<String> skipped, String next, boolean done,
                         int total) {}

  public record QuickStartView(JsonNode answers, Progress progress) {}

  public QuickStartView get(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode state = state(owner);
    return new QuickStartView(state.path("answers"), progress(state));
  }

  /** Saves one step's answers (or its skip) and returns the progress. */
  @Transactional
  public Progress save(UUID caller, String step, JsonNode answers, boolean skip) {
    if (step == null || !STEPS.contains(step)) {
      throw new ValidationException("step must be one of " + String.join(", ", STEPS) + ".");
    }
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode state = state(owner);
    ObjectNode created = state.with("created");
    if (skip) {
      mark(state, step, "skipped");
    } else {
      if (answers == null || !answers.isObject()) throw new ValidationException("Send the answers as an object.");
      List<String> ids = switch (step) {
        case "people" -> savePeople(caller, owner, answers);
        case "income" -> saveIncome(owner, answers);
        case "spending" -> saveSpending(owner, answers);
        case "loans" -> saveLoans(caller, owner, answers, ids(created, step));
        case "insurance" -> savePolicies(caller, owner, answers, ids(created, step));
        case "savings" -> saveSavings(caller, owner, answers, ids(created, step));
        case "goals" -> saveGoals(caller, owner, answers, ids(created, step));
        case "risk" -> saveRisk(caller, owner, answers);
        default -> throw new IllegalStateException(step);
      };
      if (List.of("loans", "insurance", "savings", "goals").contains(step)) {
        ArrayNode list = created.putArray(step);
        ids.forEach(list::add);
      }
      state.with("answers").set(step, answers);
      mark(state, step, "completed");
    }
    save(owner, state);
    audit.record(owner, ActorType.USER, caller, "finance.quickstart." + (skip ? "skipped" : "saved"), step, null);
    return progress(state);
  }

  // ── steps ───────────────────────────────────────────────────────────────────

  private List<String> savePeople(UUID caller, UUID owner, JsonNode a) {
    JsonNode me = a.path("me");
    if (me.isObject() && me.hasNonNull("dob")) {
      UUID self = forms.selfId(owner);
      people.update(caller, self, new FinPersonUseCase.PersonInput(null, null, null,
          parseDate(me.get("dob").asText()), me.has("isEarning") ? me.get("isEarning").asBoolean() : null, null));
    }
    JsonNode list = a.path("people");
    if (!list.isMissingNode() && !list.isArray()) throw new ValidationException("people must be a list.");
    if (list.size() > MAX_ITEMS) throw new ValidationException("Add at most " + MAX_ITEMS + " people here.");
    List<String> out = new ArrayList<>();
    for (JsonNode p : list) {
      var input = new FinPersonUseCase.PersonInput(text(p, "displayName"), text(p, "kind"),
          text(p, "type"), p.hasNonNull("dob") ? parseDate(p.get("dob").asText()) : null,
          p.has("isEarning") ? p.get("isEarning").asBoolean() : null,
          p.has("isDependent") ? p.get("isDependent").asBoolean() : null);
      var row = p.hasNonNull("id") ? people.update(caller, FinRecordsUseCase.uuidOf("id", p.get("id")), input)
          : people.create(caller, input);
      out.add(row.id().toString());
      if (p.isObject()) ((ObjectNode) p).put("id", row.id().toString());
    }
    return out;
  }

  private List<String> saveIncome(UUID owner, JsonNode a) {
    JsonNode list = a.path("incomes");
    if (!list.isArray()) throw new ValidationException("incomes must be a list.");
    if (list.size() > MAX_ITEMS) throw new ValidationException("Too many incomes.");
    List<String> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode i : list) {
      ObjectNode body = Json.obj();
      body.set("personId", i.get("personId"));
      body.set("takeHomeMonthly", i.get("takeHomeMonthly"));
      if (i.hasNonNull("grossMonthly")) body.set("grossMonthly", i.get("grossMonthly"));
      ObjectNode v = forms.clean(owner, FinTable.INCOME, body, true);
      String person = v.path("personId").asText();
      if (!seen.add(person)) throw new ValidationException("A person appears twice.");
      var existing = records.list("fin_income_source", owner, Map.of("personId", person, "kind", "salary"));
      if (existing.isEmpty()) {
        out.add(records.insert("fin_income_source", owner, v.put("kind", "salary")).path("id").asText());
      } else {
        UUID id = UUID.fromString(existing.get(0).path("id").asText());
        records.update("fin_income_source", owner, id, v);
        out.add(id.toString());
      }
    }
    return out;
  }

  private List<String> saveSpending(UUID owner, JsonNode a) {
    List<String> out = new ArrayList<>();
    budget(owner, "essentials", a.get("essentialMonthly"), out);
    budget(owner, "lifestyle", a.get("otherMonthly"), out);
    budget(owner, "investing", a.get("investingMonthly"), out);
    return out;
  }

  private void budget(UUID owner, String key, JsonNode amount, List<String> out) {
    if (amount == null || amount.isNull()) return;
    UUID category = cashFlow.categoryId(owner, key);
    ObjectNode body = Json.obj().put("categoryId", category.toString());
    body.set("monthlyAmount", amount);
    ObjectNode v = forms.clean(owner, FinTable.BUDGET, body, true);
    var existing = records.list("fin_expense_budget", owner, Map.of("categoryId", category.toString()));
    if (existing.isEmpty()) {
      out.add(records.insert("fin_expense_budget", owner, v.put("source", "manual")).path("id").asText());
    } else {
      UUID id = UUID.fromString(existing.get(0).path("id").asText());
      records.update("fin_expense_budget", owner, id, v);
      out.add(id.toString());
    }
  }

  private List<String> saveLoans(UUID caller, UUID owner, JsonNode a, List<String> before) {
    JsonNode list = a.path("loans");
    if (!list.isArray()) throw new ValidationException("loans must be a list (it can be empty).");
    if (list.size() > MAX_ITEMS) throw new ValidationException("Add at most " + MAX_ITEMS + " loans here.");
    before.forEach(id -> records.delete("fin_liability", owner, UUID.fromString(id)));
    List<String> out = new ArrayList<>();
    for (JsonNode l : list) {
      ObjectNode body = Json.obj();
      copy(l, body, "personId", "kind", "lenderName", "isPrivate", "outstanding", "rateInput",
          "repaymentMode", "startDate", "principalTaken");
      if (l.hasNonNull("monthlyPayment")) body.set("emiOrInterest", l.get("monthlyPayment"));
      if (l.hasNonNull("dueDate")) body.set("maturityOrPromisedDate", l.get("dueDate"));
      if (l.hasNonNull("grams") || l.hasNonNull("goldValue")) {
        ObjectNode pledge = Json.obj().put("assetKind", "gold");
        copy(l, pledge, "grams", "purityKarat");
        if (l.hasNonNull("goldValue")) pledge.set("valuation", l.get("goldValue"));
        body.putArray("pledges").add(pledge);
      }
      out.add(loans.create(caller, body).path("id").asText());
    }
    return out;
  }

  private List<String> savePolicies(UUID caller, UUID owner, JsonNode a, List<String> before) {
    JsonNode list = a.path("policies");
    if (!list.isArray()) throw new ValidationException("policies must be a list (it can be empty).");
    if (list.size() > MAX_ITEMS) throw new ValidationException("Add at most " + MAX_ITEMS + " policies here.");
    // The policies this step wrote before, still there. A policy answered again (same holder and
    // kind) is changed in place, so what Vita keeps on it (claims, card, policy number) stays.
    List<ObjectNode> prior = new ArrayList<>();
    for (String id : before) records.get("fin_insurance_policy", owner, UUID.fromString(id)).ifPresent(prior::add);
    List<String> out = new ArrayList<>();
    for (JsonNode p : list) {
      ObjectNode body = Json.obj();
      copy(p, body, "personId", "kind", "sumAssured", "premium", "premiumFrequency",
          "employerProvided", "coveredPersonIds", "renewalDate");
      ObjectNode same = null;
      for (ObjectNode r : prior) {
        if (r.path("kind").asText().equals(body.path("kind").asText())
            && (!body.hasNonNull("personId") || r.path("personId").asText().equals(body.path("personId").asText()))) {
          same = r;
          break;
        }
      }
      if (same != null) {
        prior.remove(same);
        out.add(forms.update(caller, FinTable.POLICY, UUID.fromString(same.path("id").asText()), body).path("id").asText());
      } else {
        out.add(forms.create(caller, FinTable.POLICY, body, null).path("id").asText());
      }
    }
    // A policy left out of the new answers goes, unless Vita holds claims or details on it: then it
    // stays as an ordinary policy, no longer this step's, to be removed where those are kept.
    for (ObjectNode r : prior) {
      UUID id = UUID.fromString(r.path("id").asText());
      boolean inVita = !vita.list("vita_claim", owner, Map.of("policyId", id)).isEmpty()
          || !vita.list("vita_policy_detail", owner, Map.of("policyId", id)).isEmpty();
      if (!inVita) records.delete("fin_insurance_policy", owner, id);
    }
    return out;
  }

  private List<String> saveSavings(UUID caller, UUID owner, JsonNode a, List<String> before) {
    JsonNode list = a.path("totals");
    if (!list.isArray()) throw new ValidationException("totals must be a list (it can be empty).");
    if (list.size() > MAX_SAVINGS) throw new ValidationException("Too many lines.");
    before.forEach(id -> records.delete("fin_asset", owner, UUID.fromString(id)));
    List<String> out = new ArrayList<>();
    for (JsonNode t : list) {
      ObjectNode body = Json.obj();
      copy(t, body, "personId", "kind", "value", "isEmergency", "name", "assetClass");
      body.put("isEstimate", true);
      body.put("valueAsOf", com.jizza.platform.usecase.finance.health.HouseholdLoader.today().toString());
      out.add(forms.create(caller, FinTable.ASSET, body, null).path("id").asText());
    }
    return out;
  }

  private List<String> saveGoals(UUID caller, UUID owner, JsonNode a, List<String> before) {
    JsonNode list = a.path("goals");
    if (!list.isArray()) throw new ValidationException("goals must be a list (it can be empty).");
    if (list.size() > MAX_GOALS) throw new ValidationException("Pick up to " + MAX_GOALS + " goals here; add more later.");
    goals.removeQuickStartGoals(owner, before);
    List<String> out = new ArrayList<>();
    for (JsonNode g : list) {
      ObjectNode body = Json.obj();
      copy(g, body, "type", "name", "targetAmountToday", "targetDate", "ownerPersonId", "currentValue",
          "monthlyContribution");
      out.add(goals.create(caller, body, "quick_start").goal().path("id").asText());
    }
    return out;
  }

  private List<String> saveRisk(UUID caller, UUID owner, JsonNode a) {
    UUID person = a.hasNonNull("personId") ? FinRecordsUseCase.uuidOf("personId", a.get("personId")) : forms.selfId(owner);
    return List.of(risk.assess(caller, person, a.get("answers")).path("id").asText());
  }

  // ── state ───────────────────────────────────────────────────────────────────

  private ObjectNode state(UUID owner) {
    var profile = store.ensureProfile(owner, FinProfileUseCase.SELF_NAME);
    JsonNode qs = profile.settings().path("quickStart");
    return qs.isObject() ? ((ObjectNode) qs).deepCopy() : Json.obj();
  }

  private void save(UUID owner, ObjectNode state) {
    var profile = store.ensureProfile(owner, FinProfileUseCase.SELF_NAME);
    ObjectNode settings = profile.settings() instanceof ObjectNode o ? o.deepCopy() : Json.obj();
    settings.set("quickStart", state);
    store.saveSettings(owner, settings);
  }

  private static void mark(ObjectNode state, String step, String as) {
    for (String list : List.of("completed", "skipped")) {
      ArrayNode arr = state.withArray(list);
      for (int i = arr.size() - 1; i >= 0; i--) if (step.equals(arr.get(i).asText())) arr.remove(i);
    }
    state.withArray(as).add(step);
  }

  public static Progress progress(JsonNode state) {
    List<String> completed = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    state.path("completed").forEach(n -> completed.add(n.asText()));
    state.path("skipped").forEach(n -> skipped.add(n.asText()));
    String next = STEPS.stream().filter(s -> !completed.contains(s) && !skipped.contains(s))
        .findFirst().orElse(null);
    return new Progress(completed, skipped, next, next == null, STEPS.size());
  }

  /** "not_started", "in_progress" or "done", for the profile's setup progress. */
  public static String status(JsonNode settings) {
    JsonNode qs = settings.path("quickStart");
    if (!qs.isObject() || (qs.path("completed").isEmpty() && qs.path("skipped").isEmpty())) return "not_started";
    return progress(qs).done() ? "done" : "in_progress";
  }

  private static List<String> ids(ObjectNode created, String step) {
    List<String> out = new ArrayList<>();
    created.path(step).forEach(n -> out.add(n.asText()));
    return out;
  }

  private static void copy(JsonNode from, ObjectNode to, String... fields) {
    for (String f : fields) if (from.has(f)) to.set(f, from.get(f));
  }

  private static String text(JsonNode n, String f) {
    return n.hasNonNull(f) ? n.get(f).asText() : null;
  }

  private static LocalDate parseDate(String s) {
    try {
      return LocalDate.parse(s);
    } catch (Exception e) {
      throw new ValidationException("dob must be a date (yyyy-mm-dd).");
    }
  }
}
