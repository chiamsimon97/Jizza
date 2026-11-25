package com.jizza.platform.usecase.finance.health;

import static com.jizza.platform.usecase.finance.health.MoneyHealthService.AMBER;
import static com.jizza.platform.usecase.finance.health.MoneyHealthService.DEBT;
import static com.jizza.platform.usecase.finance.health.MoneyHealthService.EMERGENCY;
import static com.jizza.platform.usecase.finance.health.MoneyHealthService.GOALS;
import static com.jizza.platform.usecase.finance.health.MoneyHealthService.GREEN;
import static com.jizza.platform.usecase.finance.health.MoneyHealthService.PROTECTION;
import static com.jizza.platform.usecase.finance.health.MoneyHealthService.RED;
import static com.jizza.platform.usecase.finance.health.MoneyHealthService.SAVINGS;
import static com.jizza.platform.usecase.finance.health.MoneyHealthService.SPENDING;
import static com.jizza.platform.usecase.finance.model.Inr.nz;
import static com.jizza.platform.usecase.finance.model.Inr.pct;
import static com.jizza.platform.usecase.finance.model.Inr.rupees;
import static com.jizza.platform.usecase.finance.model.Inr.words;

import com.jizza.platform.usecase.finance.debt.DebtPlanner;
import com.jizza.platform.usecase.finance.health.MoneyHealthService.Check;
import com.jizza.platform.usecase.finance.health.MoneyHealthService.Health;
import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Household.Loan;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.model.Inr;
import com.jizza.platform.usecase.finance.model.KaiConfig;
import com.jizza.platform.usecase.finance.protection.ProtectionCalculator.Gap;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The action plan (5.8): one action for every amber or red check, ranked by layer (Survival,
 * Important, Aspirational, Hygiene), inside Survival in the order of the priority ladder (starter
 * buffer or high-interest debt, then protection, then the full emergency fund), then by score
 * impact and due date. Important actions wait while any Survival check is red. Pure.
 */
public final class ActionPlanService {

  private ActionPlanService() {}

  public static final String SURVIVAL = "survival";
  public static final String IMPORTANT = "important";
  public static final String HYGIENE = "hygiene";
  private static final List<String> LAYERS = List.of(SURVIVAL, IMPORTANT, "aspirational", HYGIENE);

  public static final String STARTER = "emergency.starter";
  public static final String HIGH_DEBT = "debt.high_interest";
  public static final String PROTECT = "protection.gaps";
  public static final String EMERGENCY_FULL = "emergency.full";

  /** An action as the plan proposes it; the use case keeps its status. */
  public record Action(String key, String layer, int ladderStep, String title, String reason,
                       BigDecimal impact, String sourceCheck, String personId, LocalDate dueDate,
                       Map<String, Object> details) {}

  public static List<Action> plan(Household h, Health health, KaiConfig cfg) {
    List<Action> out = new ArrayList<>();
    var cf = health.cashFlow();
    var survival = cfg.survival();

    // High-interest debt, repaid from the surplus (never from the emergency fund).
    List<Loan> high = h.loans().stream()
        .filter(l -> nz(l.ratePct()).compareTo(survival.highInterestRatePct()) > 0)
        .filter(l -> nz(l.outstanding()).signum() > 0).toList();
    Check emergency = health.check(EMERGENCY);
    boolean starterOpen = emergency.known()
        && emergency.value().compareTo(survival.starterBufferMonths()) < 0;
    Action debtAction = null;
    if (!high.isEmpty()) {
      BigDecimal surplus = starterOpen ? BigDecimal.ZERO : cf.surplus().max(BigDecimal.ZERO);
      var plan = DebtPlanner.plan(h.loans(), surplus, h.asOf(), cfg, null, BigDecimal.ZERO);
      YearMonth first = YearMonth.from(h.asOf()).plusMonths(1);
      BigDecimal extra = BigDecimal.ZERO;
      YearMonth cleared = first;
      boolean allCleared = true;
      var firstRow = plan.month(first);
      for (Loan l : high) {
        if (firstRow != null) extra = extra.add(firstRow.payments().getOrDefault(l.id(), BigDecimal.ZERO));
        var o = plan.outcome(l.id());
        if (o == null || o.debtFreeBy() == null) allCleared = false;
        else if (o.debtFreeBy().isAfter(cleared)) cleared = o.debtFreeBy();
      }
      Integer months = allCleared ? (int) ChronoUnit.MONTHS.between(first, cleared) + 1 : null;
      BigDecimal total = high.stream().map(l -> nz(l.outstanding())).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal emis = high.stream().map(l -> nz(l.monthlyPayment())).reduce(BigDecimal.ZERO, BigDecimal::add);
      String title = high.size() == 1
          ? "Repay the " + words(total) + " " + loanName(high.get(0)) + " at " + Inr.pctWords(high.get(0).ratePct())
          : "Repay " + high.size() + " loans above " + Inr.pctWords(survival.highInterestRatePct()) + " (" + words(total) + ")";
      String reason;
      if (extra.signum() > 0) {
        reason = "Use the " + words(extra) + " unallocated monthly surplus"
            + (emis.signum() > 0 ? " on top of the " + words(emis) + " EMI" : "")
            + (months != null ? ": cleared in about " + months + " month" + (months == 1 ? "" : "s") : "")
            + ". The emergency fund is not used for this.";
      } else {
        reason = starterOpen
            ? "First build the starter emergency fund; then the monthly surplus goes to this loan."
            : "There is no monthly surplus to put towards it yet: look at spending first.";
      }
      CheckOutcome after = debtAfterRepaying(h, health, cfg, high);
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("loanIds", high.stream().map(Loan::id).toList());
      d.put("outstanding", rupees(total));
      d.put("monthlyExtra", rupees(extra));
      d.put("months", months);
      d.put("fundingSource", "monthly_surplus");
      d.put("debtFreeBy", allCleared ? cleared.toString() : null);
      debtAction = new Action(HIGH_DEBT, SURVIVAL, 1, title, reason, after.impact(), DEBT, null, null, d);
    }

    // Emergency fund: the starter buffer first, the full fund after debt and protection.
    if (emergency.known() && !GREEN.equals(emergency.status())) {
      BigDecimal essential = cf.essential();
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("months", emergency.shown());
      d.put("essentialMonthly", rupees(essential));
      if (starterOpen) {
        BigDecimal target = rupees(essential.multiply(survival.starterBufferMonths()));
        BigDecimal gap = target.subtract(health.liquidEmergency()).max(BigDecimal.ZERO);
        d.put("target", target);
        d.put("gap", rupees(gap));
        BigDecimal impact = MoneyHealthService.pointsFor(AMBER, emergency.weight()).subtract(emergency.points());
        out.add(new Action(STARTER, SURVIVAL, 0, "Build a starter emergency fund of " + words(target),
            "Emergency money covers " + emergency.shown() + " months of essential spending. Reach "
                + plain(survival.starterBufferMonths()) + " months first (a gap of " + words(gap)
                + "); no new goal SIP until then.", impact, EMERGENCY, null, null, d));
      } else {
        BigDecimal months = survival.fullEmergencyMonths();
        BigDecimal target = rupees(essential.multiply(months));
        BigDecimal gap = target.subtract(health.liquidEmergency()).max(BigDecimal.ZERO);
        d.put("target", target);
        d.put("gap", rupees(gap));
        String reason = "It covers " + emergency.shown() + " months of essential spending; the target is "
            + plain(months) + " months: " + words(target) + " today (" + plain(months) + " × "
            + Inr.group(essential) + ", a gap of " + words(gap) + ")";
        if (debtAction != null) {
          BigDecimal emis = high.stream().map(l -> nz(l.monthlyPayment())).reduce(BigDecimal.ZERO, BigDecimal::add);
          BigDecimal later = rupees(essential.subtract(emis).multiply(months));
          d.put("targetAfterDebt", later);
          reason += ", or " + words(later) + " once the loan EMI stops. Start after the loan is repaid.";
        } else {
          reason += ".";
        }
        BigDecimal impact = emergency.weight().subtract(emergency.points());
        out.add(new Action(EMERGENCY_FULL, SURVIVAL, 3, "Grow the emergency fund to " + words(target),
            reason, impact, EMERGENCY, null, null, d));
      }
    }
    if (debtAction != null) out.add(debtAction);

    // Protection gaps.
    Check protection = health.check(PROTECTION);
    if (protection.known() && !GREEN.equals(protection.status())) {
      List<Gap> gaps = health.protection().gaps();
      List<String> parts = new ArrayList<>();
      String term = gaps.stream().filter(g -> "term".equals(g.kind()))
          .map(g -> words(g.amount()) + " for " + name(h, g.personIds().get(0)))
          .collect(Collectors.joining(" and "));
      if (!term.isEmpty()) parts.add("term cover of about " + term);
      for (Gap g : gaps) {
        if (!"health".equals(g.kind())) continue;
        boolean parent = g.personIds().size() == 1 && "parent".equals(kind(h, g.personIds().get(0)));
        parts.add(parent
            ? "a separate health policy of at least " + words(g.amount()) + " for " + name(h, g.personIds().get(0))
            : "a personal family floater of at least " + words(g.amount()) + " with a super top-up");
      }
      String reason = "Close " + gaps.size() + " gap" + (gaps.size() == 1 ? "" : "s") + ": "
          + String.join("; ", parts) + ". Employer cover is shown but does not count. Kai never "
          + "recommends an insurer.";
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("gaps", gaps.stream().map(g -> Map.of("kind", g.kind(), "personIds", g.personIds(),
          "amount", g.amount())).toList());
      out.add(new Action(PROTECT, SURVIVAL, 2, "Close protection gaps", reason,
          protection.weight().subtract(protection.points()), PROTECTION, null, null, d));
    }

    // Important layer: waits while any Survival check is red.
    boolean survivalRed = List.of(EMERGENCY, DEBT, PROTECTION).stream()
        .anyMatch(k -> RED.equals(health.check(k).status()));
    if (!survivalRed) {
      Check savings = health.check(SAVINGS);
      if (savings.known() && !GREEN.equals(savings.status())) {
        BigDecimal target = rupees(cf.takeHome().multiply(savings.target()).divide(Inr.HUNDRED));
        out.add(new Action("savings.raise", IMPORTANT, 0, "Invest " + plain(savings.target())
                + "% of take-home each month (" + words(target) + ")",
            "Today " + savings.shown() + "% of take-home goes into investments.",
            savings.weight().subtract(savings.points()), SAVINGS, null, null,
            Map.of("target", target, "current", rupees(cf.save()))));
      }
      Check goals = health.check(GOALS);
      if (goals.known() && !GREEN.equals(goals.status())) {
        out.add(new Action("goals.fund", IMPORTANT, 0, "Bring important goals on track",
            "Important goals are " + goals.shown() + "% funded; the target is " + plain(goals.target()) + "%.",
            goals.weight().subtract(goals.points()), GOALS, null, null, Map.of()));
      }
      Check spending = health.check(SPENDING);
      if (spending.known() && !GREEN.equals(spending.status())) {
        out.add(new Action("spending.needs", IMPORTANT, 0, "Bring essential spending under "
                + plain(spending.target()) + "% of take-home",
            "Needs take " + spending.shown() + "% of take-home today.",
            spending.weight().subtract(spending.points()), SPENDING, null, null, Map.of()));
      }
    }

    // Hygiene: the two flags.
    if (health.flags().nomineeMissing()) {
      out.add(new Action("hygiene.nominees", HYGIENE, 0, "Add nominees to your accounts",
          "At least one account has no nominee.", BigDecimal.ZERO, null, null, null, Map.of()));
    }
    if (health.flags().willMissing()) {
      out.add(new Action("hygiene.will", HYGIENE, 0, "Record a will",
          "No will is recorded.", BigDecimal.ZERO, null, null, null, Map.of()));
    }

    out.sort(Comparator.comparing((Action a) -> LAYERS.indexOf(a.layer()))
        .thenComparing(Action::ladderStep)
        .thenComparing(Action::impact, Comparator.reverseOrder())
        .thenComparing(a -> a.dueDate() == null ? LocalDate.MAX : a.dueDate()));
    return List.copyOf(out);
  }

  private record CheckOutcome(BigDecimal impact) {}

  /** Points the Debt check gains once the high-interest loans are gone. */
  private static CheckOutcome debtAfterRepaying(Household h, Health health, KaiConfig cfg, List<Loan> high) {
    Check debt = health.check(DEBT);
    if (!debt.known()) return new CheckOutcome(BigDecimal.ZERO);
    var rule = cfg.check(DEBT);
    BigDecimal removed = high.stream().map(l -> nz(l.monthlyPayment())).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal ratio = pct(health.cashFlow().debtService().subtract(removed), health.cashFlow().gross());
    boolean otherHigh = h.loans().stream().filter(l -> !high.contains(l))
        .anyMatch(l -> nz(l.ratePct()).compareTo(rule.extra()) > 0);
    BigDecimal after = otherHigh ? BigDecimal.ZERO : MoneyHealthService.lowerStatusPoints(ratio, rule);
    return new CheckOutcome(after.subtract(debt.points()).max(BigDecimal.ZERO));
  }

  private static String loanName(Loan l) {
    return switch (l.kind()) {
      case "CREDIT_CARD" -> "credit card balance";
      case "CONSUMER_EMI" -> "consumer EMI";
      case "GOLD_LOAN" -> "gold loan";
      case "FAMILY_FRIEND" -> "family loan";
      case "MONEYLENDER" -> "moneylender loan";
      default -> l.kind().toLowerCase(Locale.ROOT).replace('_', ' ') + " loan";
    };
  }

  private static String name(Household h, String personId) {
    return h.people().stream().filter(p -> p.id().equals(personId)).map(Person::name)
        .findFirst().orElse("someone");
  }

  private static String kind(Household h, String personId) {
    return h.people().stream().filter(p -> p.id().equals(personId)).map(Person::kind)
        .findFirst().orElse("other");
  }

  private static String plain(BigDecimal v) {
    BigDecimal s = v.stripTrailingZeros();
    return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
  }
}
