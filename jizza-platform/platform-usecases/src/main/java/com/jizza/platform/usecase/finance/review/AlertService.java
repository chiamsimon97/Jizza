package com.jizza.platform.usecase.finance.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.finance.cashflow.FinCashFlowUseCase;
import com.jizza.platform.usecase.finance.debt.DebtPlanner;
import com.jizza.platform.usecase.finance.health.FinHealthUseCase;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.health.MoneyHealthService;
import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Inr;
import com.jizza.platform.usecase.finance.model.KaiConfig;
import com.jizza.platform.usecase.finance.profile.FinProfileUseCase;
import com.jizza.platform.usecase.finance.tools.FinanceToolProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Kai's alerts (5.11) and the monthly review. Alerts are Jizza notifications from Kai, each kind
 * mutable in Settings ({@code settings.mutedAlerts}). The same alert is raised once per window,
 * so the daily job never repeats itself. Bodies name dates, never amounts.
 */
@Service
public class AlertService {

  public static final String RENEWAL = "policy_renewal";
  public static final String MATURITY = "loan_maturity";
  public static final String GOLD_LTV = "gold_ltv";
  public static final String MAJORITY = "child_majority";
  public static final String EMERGENCY_LOW = "emergency_low";
  public static final String VERIFY = "needs_verification";
  public static final List<String> KINDS = List.of(RENEWAL, MATURITY, GOLD_LTV, MAJORITY, EMERGENCY_LOW, VERIFY);

  static final int RENEWAL_DAYS = 30;
  static final int MAJORITY_DAYS = 90;
  private static final Duration REPEAT_WINDOW = Duration.ofDays(30);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

  private final HouseholdLoader loader;
  private final FinanceStore store;
  private final FinanceRecords records;
  private final NotificationBus bus;
  private final AgentDirectory agents;
  private final FinHealthUseCase health;
  private final FinCashFlowUseCase cashFlow;
  private final VitaRecords vita;

  public AlertService(HouseholdLoader loader, FinanceStore store, FinanceRecords records,
                      NotificationBus bus, AgentDirectory agents, FinHealthUseCase health,
                      FinCashFlowUseCase cashFlow, VitaRecords vita) {
    this.vita = vita;
    this.loader = loader;
    this.store = store;
    this.records = records;
    this.bus = bus;
    this.agents = agents;
    this.health = health;
    this.cashFlow = cashFlow;
  }

  /** One alert: its kind, the sentence, and the Portfolio view it links to. */
  public record Alert(String kind, String body, String view) {}

  /** The alerts due for an owner today, before muting and repeat checks. Pure over the data. */
  public static List<Alert> due(Household h, KaiConfig cfg, Map<String, Boolean> verification) {
    return due(h, cfg, verification, Set.of());
  }

  /**
   * As above, leaving out the renewal of each policy in {@code remindedByVita}: Vita already keeps
   * a renewal reminder for it, and one reminder per renewal is enough.
   */
  public static List<Alert> due(Household h, KaiConfig cfg, Map<String, Boolean> verification,
                                Set<String> remindedByVita) {
    LocalDate today = h.asOf();
    List<Alert> out = new ArrayList<>();
    for (var p : h.policies()) {
      if (p.renewalDate() == null || remindedByVita.contains(p.id())) continue;
      long days = ChronoUnit.DAYS.between(today, p.renewalDate());
      if (days >= 0 && days <= RENEWAL_DAYS) {
        out.add(new Alert(RENEWAL, "A " + p.kind().replace('_', ' ') + " policy renews on "
            + DAY.format(p.renewalDate()) + ".", "protection"));
      }
    }
    for (var l : h.loans()) {
      if (l.dueDate() == null || !"bullet".equals(l.mode())) continue;
      long days = ChronoUnit.DAYS.between(today, l.dueDate());
      if (days >= 0 && days <= cfg.goldLtv().warnDaysBeforeMaturity()) {
        out.add(new Alert(MATURITY, "The " + kindWords(l.kind()) + " falls due on " + DAY.format(l.dueDate())
            + ". Check the repayment plan.", "loans"));
      }
      if ("GOLD_LOAN".equals(l.kind())) {
        var g = DebtPlanner.goldLoan(l, today, cfg);
        if (g.warn()) {
          out.add(new Alert(GOLD_LTV, "Your gold loan's loan-to-value is " + g.ltvPct()
              + "%, near the " + g.capPct() + "% cap. A fall in the gold price could bring a "
              + "margin call.", "loans"));
        }
      }
    }
    for (var p : h.people()) {
      if (!"child".equals(p.kind()) || p.dob() == null) continue;
      LocalDate eighteen = p.dob().plusYears(18);
      long days = ChronoUnit.DAYS.between(today, eighteen);
      if (days >= 0 && days <= MAJORITY_DAYS) {
        out.add(new Alert(MAJORITY, p.name() + " turns 18 on " + DAY.format(eighteen)
            + ". Their accounts and goals can move to their own Jizza account once they have one.",
            "people"));
      }
    }
    if (h.knows(Household.SPENDING) && h.knows(Household.SAVINGS)) {
      var result = MoneyHealthService.run(h, cfg);
      if (result.emergencyMonths() != null
          && result.emergencyMonths().compareTo(cfg.survival().starterBufferMonths()) < 0) {
        out.add(new Alert(EMERGENCY_LOW, "Your emergency fund covers less than "
            + Inr.one(cfg.survival().starterBufferMonths()).stripTrailingZeros().toPlainString()
            + " months of essential spending.", "actions"));
      }
    }
    if (verification.values().stream().anyMatch(Boolean::booleanValue)) {
      out.add(new Alert(VERIFY, "Some tax or regulatory figures Kai uses are not verified for "
          + "this financial year yet. Results that use them carry a badge.", "settings"));
    }
    return out;
  }

  /** The daily job's work for one owner: raises what is due and not muted or already raised. */
  public int sendAlerts(UUID owner) {
    var loaded = loader.load(owner);
    Set<String> muted = muted(owner);
    UUID kai = agents.byKey(owner, FinanceToolProvider.AGENT_KEY).map(a -> a.id()).orElse(null);
    int sent = 0;
    Instant since = Instant.now().minus(REPEAT_WINDOW);
    for (Alert a : due(loaded.household(), loaded.config(), loaded.verification(), remindedByVita(owner))) {
      if (muted.contains(a.kind())) continue;
      if (bus.raisedSince(owner, kai, NotificationKind.INFO, a.body(), since)) continue;
      var detail = Json.obj().put("source", "kai_portfolio").put("alert", a.kind()).put("view", a.view());
      bus.raise(NotificationKind.INFO, kai,
          new NotificationContext(owner, a.body(), detail, null, null, null, null));
      sent++;
    }
    return sent;
  }

  /**
   * The policies whose renewal Vita reminds about: a live renewal reminder of this policy year
   * (scheduled, sent or dismissed; not cancelled). Empty while health is off, as Vita adds none.
   */
  Set<String> remindedByVita(UUID owner) {
    Map<String, Object> where = new java.util.LinkedHashMap<>();
    where.put("kind", "policy_renewal");
    where.put("deletedAt", null);
    Instant yearAgo = Instant.now().minus(Duration.ofDays(366));
    Set<String> out = new HashSet<>();
    for (var r : vita.list("vita_reminder", owner, where)) {
      if ("cancelled".equals(r.path("status").asText()) || !r.hasNonNull("refId")) continue;
      Instant due;
      try {
        due = java.time.OffsetDateTime.parse(r.path("dueAt").asText()).toInstant();
      } catch (RuntimeException e) {
        continue;
      }
      if (due.isAfter(yearAgo)) out.add(r.get("refId").asText());
    }
    return out;
  }

  /** The monthly job's work for one owner: net-worth snapshot, health check, monthly review. */
  public void monthly(UUID owner) {
    cashFlow.snapshot(owner);
    health.runFor(owner);
    var checklist = Json.mapper().createArrayNode();
    for (String item : List.of("imports", "health_score", "sip_matching", "drift")) {
      checklist.addObject().put("key", item).put("status", "pending");
    }
    var run = Json.obj().put("kind", "monthly");
    run.set("checklist", checklist);
    try {
      records.insert("fin_review_run", owner, run);
    } catch (DataIntegrityViolationException alreadyThisMonth) {
      // The monthly review for this month exists; the job is idempotent.
    }
  }

  private Set<String> muted(UUID owner) {
    var profile = store.ensureProfile(owner, FinProfileUseCase.SELF_NAME);
    Set<String> out = new HashSet<>();
    JsonNode list = profile.settings().path("mutedAlerts");
    list.forEach(n -> out.add(n.asText()));
    return out;
  }

  private static String kindWords(String kind) {
    return switch (kind) {
      case "GOLD_LOAN" -> "gold loan";
      case "FAMILY_FRIEND" -> "family loan";
      default -> kind.toLowerCase(Locale.ROOT).replace('_', ' ') + (kind.endsWith("LOAN") ? "" : " loan");
    };
  }
}
