package com.jizza.platform.usecase.finance.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.config.FinConfigService;
import com.jizza.platform.usecase.finance.goal.FundingStatusService.Funding;
import com.jizza.platform.usecase.finance.goal.FundingStatusService.GoalInput;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.health.HouseholdLoader.Loaded;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.model.Inr;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import com.jizza.platform.usecase.finance.tools.FinanceToolProvider;
import com.jizza.platform.usecase.finance.tree.TreeService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The goal check: does each goal meet its target? One verdict per live goal, from what is
 * earmarked to it today, what goes in each month, the chance of reaching the inflated target by
 * its date (Monte Carlo), and whether its mix sits inside the asset tree's bands.
 *
 * <ul>
 *   <li>On track: the chance is at or above the bar for the goal's priority, and the mix fits.
 *   <li>At risk: the chance is below the bar by no more than the margin, or the mix is outside a band
 *       or holds unconfirmed shares in a goal under three years away.
 *   <li>Off track: further below the bar, or nothing saved and nothing going in.
 *   <li>Not enough data: no cost or no date.
 * </ul>
 *
 * <p>{@link #runFor} stores each result as a projection run and tells the owner when a verdict
 * changes. Notifications name the goal and the verdict, never amounts. Kai only reports; nothing
 * is bought, sold or moved.
 */
@Service
public class GoalCheckService {

  public static final String ON_TRACK = "on_track";
  public static final String AT_RISK = "at_risk";
  public static final String OFF_TRACK = "off_track";
  public static final String NO_DATA = "not_enough_data";

  /** Points below the bar that still count as at risk rather than off track. */
  static final double DEFAULT_MARGIN = 15;
  static final String MARGIN_KEY = "goals.verdict_margin";
  static final String BARS_KEY = "priority.required_probability";
  static final String SIM_KEY = "returns.monte_carlo";

  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

  private final FinanceRecords records;
  private final HouseholdLoader loader;
  private final FinScopeResolver scopes;
  private final TreeService trees;
  private final NotificationBus bus;
  private final AgentDirectory agents;
  private final FinConfigService config;

  public GoalCheckService(FinanceRecords records, HouseholdLoader loader, FinScopeResolver scopes,
                          TreeService trees, NotificationBus bus, AgentDirectory agents,
                          FinConfigService config) {
    this.records = records;
    this.loader = loader;
    this.scopes = scopes;
    this.trees = trees;
    this.bus = bus;
    this.agents = agents;
    this.config = config;
  }

  /**
   * One goal's check. Money figures are in today's terms only where named so; {@code futureTarget},
   * {@code projected}, {@code p10} and {@code p50} are at the target date. {@code extraMonthly} is
   * what more a month would close the gap (zero when none is needed); {@code requiredMonthly} is the
   * whole monthly amount the deterministic plan asks for. {@code previousVerdict} is
   * the last stored run's, so the screen can say what changed.
   */
  public record Check(String goalId, String name, String priority, LocalDate targetDate, String verdict,
                      Double successPct, Double requiredPct, BigDecimal fundedPct, BigDecimal currentValue,
                      BigDecimal monthly, BigDecimal futureTarget, BigDecimal projected, BigDecimal p10,
                      BigDecimal p50, BigDecimal shortfall, BigDecimal extraMonthly, BigDecimal requiredMonthly, int partsOutsideBand,
                      int shortGoalShares, boolean earmarked, String headline, String fix,
                      String previousVerdict) {}

  /** Every live goal's check, worked out now. Stores nothing. */
  public List<Check> list(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    return compute(owner);
  }

  /** The caller's own run, from the Goals screen. */
  public List<Check> runOwn(UUID caller) {
    return runFor(scopes.own(caller).ownerUserId());
  }

  /** Works the checks out, stores them, and notifies the owner of each verdict that changed. */
  public List<Check> runFor(UUID owner) {
    List<Check> checks = compute(owner);
    UUID kai = agents.byKey(owner, FinanceToolProvider.AGENT_KEY).map(a -> a.id()).orElse(null);
    for (Check c : checks) {
      ObjectNode assumptions = Json.obj().put("verdict", c.verdict()).put("headline", c.headline());
      if (c.requiredPct() != null) assumptions.put("requiredPct", c.requiredPct());
      assumptions.put("partsOutsideBand", c.partsOutsideBand());
      ObjectNode run = Json.obj().put("planId", c.goalId()).put("method", "monte_carlo");
      if (c.fundedPct() != null) run.put("fundedPct", c.fundedPct());
      if (c.requiredMonthly() != null) run.put("requiredSip", c.requiredMonthly());
      if (c.successPct() != null) run.put("successProb", BigDecimal.valueOf(c.successPct()).setScale(4, RoundingMode.HALF_UP));
      if (c.p10() != null) run.put("p10", c.p10());
      if (c.p50() != null) run.put("p50", c.p50());
      run.set("assumptions", assumptions);
      records.insert("fin_projection_run", owner, run);
      if (c.previousVerdict() != null && !c.previousVerdict().equals(c.verdict()) && kai != null) {
        String body = c.name() + " moved from " + words(c.previousVerdict()) + " to " + words(c.verdict()) + ".";
        var detail = Json.obj().put("source", "kai_portfolio").put("alert", "goal_verdict").put("view", "goals")
            .put("goalId", c.goalId());
        bus.raise(NotificationKind.INFO, kai, new NotificationContext(owner, body, detail, null, null, null, null));
      }
    }
    return checks;
  }

  private List<Check> compute(UUID owner) {
    Loaded l = loader.load(owner);
    LocalDate today = l.household().asOf();
    Map<String, Person> people = new HashMap<>();
    l.household().people().forEach(p -> people.put(p.id(), p));
    // These three are read here: the loader keeps only the health and plan builder keys.
    Map<String, JsonNode> cfg = new HashMap<>();
    for (var item : config.list(owner)) {
      if (Set.of(BARS_KEY, MARGIN_KEY, SIM_KEY).contains(item.key())) cfg.put(item.key(), item.value());
    }
    JsonNode bars = cfg.get(BARS_KEY);
    JsonNode marginNode = cfg.get(MARGIN_KEY);
    double margin = marginNode != null && marginNode.isNumber() ? marginNode.asDouble()
        : marginNode != null && marginNode.path("points").isNumber() ? marginNode.path("points").asDouble() : DEFAULT_MARGIN;
    var assumptions = GoalSimulation.Assumptions.from(cfg.get(SIM_KEY));
    Map<String, String> previous = previousVerdicts(owner);

    List<Check> out = new ArrayList<>();
    for (ObjectNode stored : records.list("fin_plan", owner, Map.of())) {
      if (HouseholdLoader.isUnassigned(stored) || !GoalMath.LIVE.contains(stored.path("status").asText())) continue;
      ObjectNode g = l.withEarmarks(stored);
      String id = g.path("id").asText();
      String name = g.path("name").asText("Goal");
      String priority = g.path("priorityTier").asText("important");
      LocalDate date = g.hasNonNull("targetDate") ? LocalDate.parse(g.get("targetDate").asText()) : null;
      BigDecimal cost = HouseholdLoader.dec(g, "targetAmountToday");
      BigDecimal value = Inr.nz(HouseholdLoader.dec(g, "currentValue"));
      BigDecimal monthly = Inr.nz(HouseholdLoader.dec(g, "monthlyContribution"));
      boolean earmarked = g.has("earmarkedValue");
      String prev = previous.get(id);
      if (date == null || cost == null || cost.signum() <= 0 || !date.isAfter(today)) {
        out.add(new Check(id, name, priority, date, NO_DATA, null, null, null, value, monthly, null, null, null,
            null, null, null, null, 0, 0, earmarked, name + ": not enough data.",
            date == null || !date.isAfter(today) ? "Set a target date after today." : "Set what the goal costs today.", prev));
        continue;
      }
      GoalInput in = GoalMath.input(g, people, l.latestRisk(), l.selfId(), today);
      Funding f = FundingStatusService.fund(in, today, l.plan());
      long seed = id.hashCode() * 31L + today.toEpochDay();
      var sim = GoalSimulation.run(in, f.futureTarget().doubleValue(), today, l.plan(), assumptions, seed);
      double bar = bars == null ? 75 : bars.path(priority).asDouble(75);
      int outside = 0;
      int shortShares = 0;
      try {
        var alloc = trees.allocation(owner, UUID.fromString(id));
        if (alloc.treeSaved() || earmarked) outside = alloc.outsideBand();
        shortShares = (int) alloc.shortGoalShares().stream().filter(s -> !s.path("confirmed").asBoolean(false)).count();
      } catch (RuntimeException noTreeYet) {
        // A goal without holdings or a tree has no mix to check.
      }
      String verdict = verdict(sim.successPct(), bar, margin, outside + shortShares, value, monthly);
      BigDecimal shortfall = f.futureTarget().subtract(f.projected()).max(BigDecimal.ZERO);
      BigDecimal extra = f.requiredSip().signum() == 0 ? BigDecimal.ZERO : f.requiredSip().subtract(monthly).max(BigDecimal.ZERO);
      double chance = Math.round(sim.successPct() * 10) / 10.0;
      out.add(new Check(id, name, priority, date, verdict, chance, bar, f.fundedPct(), value, monthly,
          f.futureTarget(), f.projected(), Inr.rupees(BigDecimal.valueOf(sim.p10())), Inr.rupees(BigDecimal.valueOf(sim.p50())),
          shortfall, extra, f.requiredSip(), outside, shortShares, earmarked,
          headline(name, verdict, chance, f.futureTarget(), date),
          fix(verdict, extra, shortfall, outside, shortShares, value, monthly), prev));
    }
    out.sort(Comparator.comparingInt((Check c) -> rank(c.verdict())).thenComparing(c -> c.targetDate() == null ? LocalDate.MAX : c.targetDate()));
    return out;
  }

  /** The verdict from the chance, the bar for the goal's priority, and the mix. Pure. */
  static String verdict(double chance, double bar, double margin, int mixProblems, BigDecimal value, BigDecimal monthly) {
    if (value.signum() <= 0 && monthly.signum() <= 0) return OFF_TRACK;
    if (chance >= bar) return mixProblems > 0 ? AT_RISK : ON_TRACK;
    return chance >= bar - margin ? AT_RISK : OFF_TRACK;
  }

  static String headline(String name, String verdict, double chance, BigDecimal future, LocalDate date) {
    return name + ": " + words(verdict) + ". " + fmtPct(chance) + " chance of " + Inr.group(future) + " by "
        + MONTH.format(date) + ".";
  }

  static String fix(String verdict, BigDecimal extra, BigDecimal shortfall, int outside, int shortShares,
                    BigDecimal value, BigDecimal monthly) {
    if (value.signum() <= 0 && monthly.signum() <= 0) return "Nothing is set aside yet; give it units or a monthly amount.";
    List<String> parts = new ArrayList<>();
    if (!ON_TRACK.equals(verdict) && extra.signum() > 0) {
      parts.add("Short by about " + Inr.group(roundTo(shortfall, 1000)) + "; " + Inr.group(roundTo(extra, 100))
          + " more a month closes it.");
    }
    if (outside > 0) parts.add(outside == 1 ? "One part of its mix is outside its band." : outside + " parts of its mix are outside their bands.");
    if (shortShares > 0) parts.add("It holds direct shares while under three years away.");
    if (parts.isEmpty()) return ON_TRACK.equals(verdict) ? "Keep going as you are." : "Raise the monthly amount or move the date out.";
    return String.join(" ", parts);
  }

  /** Rounded up to the step, so a fix never reads as more exact than the estimate behind it. */
  static BigDecimal roundTo(BigDecimal v, int step) {
    BigDecimal s = BigDecimal.valueOf(step);
    return v.divide(s, 0, RoundingMode.CEILING).multiply(s);
  }

  public static String words(String verdict) {
    return switch (verdict) {
      case ON_TRACK -> "On track";
      case AT_RISK -> "At risk";
      case OFF_TRACK -> "Off track";
      default -> "Not enough data";
    };
  }

  private static int rank(String verdict) {
    return switch (verdict) {
      case OFF_TRACK -> 0;
      case AT_RISK -> 1;
      case NO_DATA -> 2;
      default -> 3;
    };
  }

  private static String fmtPct(double v) {
    return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + "%";
  }

  /** Each goal's verdict at its last stored run. */
  private Map<String, String> previousVerdicts(UUID owner) {
    Map<String, ObjectNode> latest = new HashMap<>();
    for (ObjectNode r : records.list("fin_projection_run", owner, Map.of("method", "monte_carlo"))) {
      String plan = r.path("planId").asText();
      ObjectNode have = latest.get(plan);
      if (have == null || r.path("runAt").asText().compareTo(have.path("runAt").asText()) > 0) latest.put(plan, r);
    }
    Map<String, String> out = new HashMap<>();
    latest.forEach((k, v) -> {
      String verdict = v.path("assumptions").path("verdict").asText("");
      if (!verdict.isEmpty()) out.put(k, verdict);
    });
    return out;
  }
}
