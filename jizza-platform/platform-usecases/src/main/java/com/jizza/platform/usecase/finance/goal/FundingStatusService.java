package com.jizza.platform.usecase.finance.goal;

import static com.jizza.platform.usecase.finance.model.Inr.HUNDRED;
import static com.jizza.platform.usecase.finance.model.Inr.nz;

import com.jizza.platform.usecase.finance.builder.AllocationResolver;
import com.jizza.platform.usecase.finance.model.Inr;
import com.jizza.platform.usecase.finance.model.PlanConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic funding status of a goal (spec 5.6). Analysis, not a forecast.
 *
 * <ul>
 *   <li>Future target = today's target × (1 + inflation)^years.
 *   <li>Projected corpus = what is saved today plus the monthly contribution (paid at the start of
 *       each month, stepped up once a year), each month grown at the blended expected return of
 *       the goal's mix. The mix follows the bucket the goal is in that month (Long, then Mid from 7
 *       years out, then Short from 3), so the return is re-blended at each bucket change.
 *   <li>Funded % = projected corpus ÷ future target.
 *   <li>Required SIP = the level monthly amount (no step-up) that makes it 100%, rounded up to the
 *       next ₹100 so it never falls short; zero when already funded.
 *   <li>Weighted funded % = Σ(funded % capped at 100 × future target) ÷ Σ future target, over the
 *       Important goals. None means "unknown".
 * </ul>
 */
public final class FundingStatusService {

  private FundingStatusService() {}

  /**
   * @param age the goal owner's age today (shapes the Long mix); null uses the 30–39 row
   * @param risk conservative, moderate or aggressive
   */
  public record GoalInput(LocalDate targetDate, BigDecimal targetToday, String inflationKey,
                          BigDecimal currentValue, BigDecimal monthly, BigDecimal stepUpPct,
                          Integer age, String risk) {}

  /**
   * @param blendedReturnNow the expected yearly return of the goal's mix today, in percent
   */
  public record Funding(long monthsToTarget, String bucket, BigDecimal inflationPct,
                        BigDecimal futureTarget, BigDecimal projected, BigDecimal fundedPct,
                        BigDecimal requiredSip, BigDecimal blendedReturnNow,
                        Map<String, BigDecimal> mixNow, Long monthsToNextBucket, String nextBucket) {}

  public static Funding fund(GoalInput g, LocalDate today, PlanConfig cfg) {
    long months = Math.max(0, ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(g.targetDate())));
    BigDecimal inflation = cfg.inflationPct(g.inflationKey());
    double years = months / 12.0;
    BigDecimal future = Inr.rupees(nz(g.targetToday())
        .multiply(BigDecimal.valueOf(Math.pow(1 + inflation.doubleValue() / 100, years))));
    int age = g.age() == null ? 35 : g.age();
    String risk = g.risk() == null ? "moderate" : g.risk();

    double corpus = nz(g.currentValue()).doubleValue();
    double unit = 0;
    double monthly = nz(g.monthly()).doubleValue();
    double step = nz(g.stepUpPct()).doubleValue() / 100;
    for (long k = 0; k < months; k++) {
      double r = blended(cfg.bucketFor(months - k), age, risk, cfg).doubleValue() / 100;
      double m = Math.pow(1 + r, 1.0 / 12) - 1;
      double contribution = monthly * Math.pow(1 + step, Math.floor(k / 12.0));
      corpus = (corpus + contribution) * (1 + m);
      unit = (unit + 1) * (1 + m);
    }
    BigDecimal projected = Inr.rupees(BigDecimal.valueOf(corpus));
    BigDecimal funded = future.signum() == 0 ? HUNDRED : Inr.pct(projected, future);

    BigDecimal required = BigDecimal.ZERO;
    if (projected.compareTo(future) < 0) {
      double withoutSip = nz(g.currentValue()).doubleValue();
      for (long k = 0; k < months; k++) {
        double r = blended(cfg.bucketFor(months - k), age, risk, cfg).doubleValue() / 100;
        withoutSip *= Math.pow(1 + r, 1.0 / 12);
      }
      double gap = future.doubleValue() - withoutSip;
      if (months == 0) {
        required = Inr.rupees(BigDecimal.valueOf(Math.max(0, gap)));
      } else if (gap > 0) {
        required = BigDecimal.valueOf(gap / unit).divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING)
            .multiply(BigDecimal.valueOf(100));
      }
    }

    String bucket = cfg.bucketFor(months);
    Long toNext = null;
    String next = null;
    if ("long".equals(bucket)) {
      toNext = months - (cfg.midBelowMonths() - 1);
      next = "mid";
    } else if ("mid".equals(bucket)) {
      toNext = months - (cfg.shortBelowMonths() - 1);
      next = "short";
    }
    return new Funding(months, bucket, inflation, future, projected, funded, required,
        blended(bucket, age, risk, cfg), mix(bucket, age, risk, cfg), toNext, next);
  }

  /** The goal's mix in a bucket: Short by Kai's default, Mid by risk, Long by age and risk. */
  public static Map<String, BigDecimal> mix(String bucket, int age, String risk, PlanConfig cfg) {
    return switch (bucket) {
      case "short" -> AllocationResolver.shortMix(cfg);
      case "mid" -> new LinkedHashMap<>(cfg.midByRisk().get(risk));
      default -> AllocationResolver.longMix(cfg.longFor(age), risk);
    };
  }

  /** The yearly expected return of a bucket's mix, in percent. */
  public static BigDecimal blended(String bucket, int age, String risk, PlanConfig cfg) {
    BigDecimal sum = BigDecimal.ZERO;
    BigDecimal weights = BigDecimal.ZERO;
    for (var e : mix(bucket, age, risk, cfg).entrySet()) {
      sum = sum.add(e.getValue().multiply(cfg.returnPct(e.getKey())));
      weights = weights.add(e.getValue());
    }
    return weights.signum() == 0 ? BigDecimal.ZERO : sum.divide(weights, 6, RoundingMode.HALF_UP);
  }

  /** Weighted funded % over important goals; null when there are none. */
  public static BigDecimal weighted(List<Funding> important) {
    BigDecimal num = BigDecimal.ZERO;
    BigDecimal den = BigDecimal.ZERO;
    for (Funding f : important) {
      num = num.add(f.fundedPct().min(HUNDRED).multiply(f.futureTarget()));
      den = den.add(f.futureTarget());
    }
    return den.signum() == 0 ? null : Inr.div(num, den);
  }
}
