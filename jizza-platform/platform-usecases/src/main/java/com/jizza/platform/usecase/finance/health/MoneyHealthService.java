package com.jizza.platform.usecase.finance.health;

import static com.jizza.platform.usecase.finance.model.Inr.HUNDRED;
import static com.jizza.platform.usecase.finance.model.Inr.div;
import static com.jizza.platform.usecase.finance.model.Inr.nz;
import static com.jizza.platform.usecase.finance.model.Inr.one;
import static com.jizza.platform.usecase.finance.model.Inr.pct;

import com.jizza.platform.usecase.finance.cashflow.CashFlowService;
import com.jizza.platform.usecase.finance.cashflow.CashFlowService.CashFlow;
import com.jizza.platform.usecase.finance.model.Household;
import com.jizza.platform.usecase.finance.model.Household.Loan;
import com.jizza.platform.usecase.finance.model.KaiConfig;
import com.jizza.platform.usecase.finance.model.KaiConfig.CheckRule;
import com.jizza.platform.usecase.finance.protection.ProtectionCalculator;
import com.jizza.platform.usecase.finance.protection.ProtectionCalculator.Protection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Money Health check (2A.3, 5.8): six weighted checks, green earning the full weight, amber
 * half and red nothing. A check whose data is missing is "unknown": it is left out and the score
 * is rescaled to 100 over the weights that are known. With nothing known there is no score.
 */
public final class MoneyHealthService {

  private MoneyHealthService() {}

  public static final String EMERGENCY = "emergencyFund";
  public static final String DEBT = "debt";
  public static final String SAVINGS = "savingsRate";
  public static final String GOALS = "goalFunding";
  public static final String PROTECTION = "protection";
  public static final String SPENDING = "spendingControl";
  public static final List<String> ORDER = List.of(EMERGENCY, DEBT, SAVINGS, GOALS, PROTECTION, SPENDING);

  public static final String GREEN = "green";
  public static final String AMBER = "amber";
  public static final String RED = "red";
  public static final String UNKNOWN = "unknown";

  /**
   * One check. {@code value} is in {@code unit} (months, percent or gaps), exact; {@code shown}
   * is it rounded to one decimal for the screen. {@code target} is the green threshold.
   */
  public record Check(String key, String status, BigDecimal value, BigDecimal shown, String unit,
                      BigDecimal target, BigDecimal weight, BigDecimal points,
                      Map<String, Object> facts) {
    public boolean known() { return !UNKNOWN.equals(status); }
  }

  /** Not scored: shown beside the score. */
  public record Flags(boolean nomineeMissing, boolean willMissing) {}

  /**
   * @param score the score out of 100 rounded half up; null when no check is known
   * @param rawScore the exact rescaled score, one decimal
   */
  public record Health(Integer score, BigDecimal rawScore, List<Check> checks, Flags flags,
                       CashFlow cashFlow, Protection protection, BigDecimal liquidEmergency,
                       BigDecimal emergencyMonths) {
    public Check check(String key) {
      return checks.stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }
  }

  public static Health run(Household h, KaiConfig cfg) {
    CashFlow cf = CashFlowService.compute(h);
    BigDecimal liquid = liquidEmergency(h);
    Protection protection = ProtectionCalculator.compute(h, cf, liquid, cfg, Map.of());
    List<Check> checks = new ArrayList<>();

    boolean income = h.knows(Household.INCOME) && cf.takeHome().signum() > 0;
    boolean spending = h.knows(Household.SPENDING);

    BigDecimal months = null;
    if (spending && h.knows(Household.SAVINGS) && cf.essential().signum() > 0) {
      months = div(liquid, cf.essential());
      checks.add(higherIsBetter(EMERGENCY, months, "months", cfg.check(EMERGENCY),
          Map.of("liquid", liquid, "essentialMonthly", cf.essential())));
    } else {
      checks.add(unknown(EMERGENCY, "months", cfg.check(EMERGENCY)));
    }

    if (income && h.knows(Household.LOANS)) {
      CheckRule rule = cfg.check(DEBT);
      BigDecimal ratio = pct(cf.debtService(), cf.gross());
      List<String> high = h.loans().stream()
          .filter(l -> nz(l.ratePct()).compareTo(rule.extra()) > 0).map(Loan::id).toList();
      String status = !high.isEmpty() ? RED : lowerStatus(ratio, rule);
      checks.add(check(DEBT, status, ratio, "pctOfGrossIncome", rule,
          Map.of("debtPayments", cf.debtService(), "grossMonthly", cf.gross(), "highInterestLoanIds", high)));
    } else {
      checks.add(unknown(DEBT, "pctOfGrossIncome", cfg.check(DEBT)));
    }

    if (income && spending) {
      checks.add(higherIsBetter(SAVINGS, pct(cf.save(), cf.takeHome()), "pctOfTakeHome",
          cfg.check(SAVINGS), Map.of("investedMonthly", cf.save(), "takeHome", cf.takeHome())));
    } else {
      checks.add(unknown(SAVINGS, "pctOfTakeHome", cfg.check(SAVINGS)));
    }

    if (h.importantGoalsFundedPct() != null) {
      checks.add(higherIsBetter(GOALS, h.importantGoalsFundedPct(), "pctFunded", cfg.check(GOALS), Map.of()));
    } else {
      checks.add(unknown(GOALS, "pctFunded", cfg.check(GOALS)));
    }

    if (income && h.knows(Household.INSURANCE)) {
      CheckRule rule = cfg.check(PROTECTION);
      BigDecimal gaps = BigDecimal.valueOf(protection.gapCount());
      checks.add(check(PROTECTION, lowerStatus(gaps, rule), gaps, "gaps", rule,
          Map.of("affordabilityFlag", protection.affordabilityFlag())));
    } else {
      checks.add(unknown(PROTECTION, "gaps", cfg.check(PROTECTION)));
    }

    if (income && spending) {
      CheckRule rule = cfg.check(SPENDING);
      BigDecimal needs = pct(cf.essential(), cf.takeHome());
      checks.add(check(SPENDING, lowerStatus(needs, rule), needs, "needsPctOfTakeHome", rule,
          Map.of("essentialMonthly", cf.essential(), "takeHome", cf.takeHome())));
    } else {
      checks.add(unknown(SPENDING, "needsPctOfTakeHome", cfg.check(SPENDING)));
    }

    BigDecimal points = BigDecimal.ZERO;
    BigDecimal weights = BigDecimal.ZERO;
    for (Check c : checks) {
      if (!c.known()) continue;
      points = points.add(c.points());
      weights = weights.add(c.weight());
    }
    Integer score = null;
    BigDecimal raw = null;
    if (weights.signum() > 0) {
      BigDecimal exact = div(points.multiply(HUNDRED), weights);
      score = exact.setScale(0, RoundingMode.HALF_UP).intValueExact();
      raw = one(exact);
    }
    boolean nominee = h.accounts().stream().anyMatch(a -> "missing".equals(a.nomineeStatus()));
    return new Health(score, raw, List.copyOf(checks), new Flags(nominee, !h.willRecorded()), cf,
        protection, liquid, months);
  }

  /** Money flagged for emergencies (savings, sweep FD, liquid fund), pledged assets excluded. */
  public static BigDecimal liquidEmergency(Household h) {
    return h.assets().stream().filter(a -> a.emergency() && !a.pledged())
        .map(a -> nz(a.value())).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static Check higherIsBetter(String key, BigDecimal value, String unit, CheckRule rule,
                                      Map<String, Object> facts) {
    String status = value.compareTo(rule.green()) >= 0 ? GREEN
        : value.compareTo(rule.amber()) >= 0 ? AMBER : RED;
    return check(key, status, value, unit, rule, facts);
  }

  private static String lowerStatus(BigDecimal value, CheckRule rule) {
    return value.compareTo(rule.green()) <= 0 ? GREEN
        : value.compareTo(rule.amber()) <= 0 ? AMBER : RED;
  }

  private static Check check(String key, String status, BigDecimal value, String unit,
                             CheckRule rule, Map<String, Object> facts) {
    BigDecimal points = switch (status) {
      case GREEN -> rule.weight();
      case AMBER -> rule.weight().divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);
      default -> BigDecimal.ZERO;
    };
    return new Check(key, status, value, one(value), unit, rule.green(), rule.weight(), points, facts);
  }

  private static Check unknown(String key, String unit, CheckRule rule) {
    return new Check(key, UNKNOWN, null, null, unit, rule.green(), rule.weight(), BigDecimal.ZERO, Map.of());
  }

  /** The points a check would earn with a given status (for an action's impact). */
  public static BigDecimal pointsFor(String status, BigDecimal weight) {
    return switch (status) {
      case GREEN -> weight;
      case AMBER -> weight.divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);
      default -> BigDecimal.ZERO;
    };
  }

  static BigDecimal lowerStatusPoints(BigDecimal value, CheckRule rule) {
    return pointsFor(lowerStatus(value, rule), rule.weight());
  }
}
