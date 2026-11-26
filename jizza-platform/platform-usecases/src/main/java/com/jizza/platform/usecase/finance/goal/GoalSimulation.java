package com.jizza.platform.usecase.finance.goal;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.platform.usecase.finance.model.PlanConfig;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The chance a goal reaches its target (spec 5.6, Monte Carlo). Each path grows what is saved today
 * plus the monthly amount, month by month, at returns drawn for equity, debt and gold with the
 * volatility and correlation in {@code returns.monte_carlo}; cash grows at its expected return.
 * The mix follows the goal's bucket month by month, as the deterministic funding does, and is
 * rebalanced each month. The random draws are seeded from the goal and the day, so the same data
 * gives the same answer all day.
 */
public final class GoalSimulation {

  private GoalSimulation() {}

  /** Paths are capped so one household's nightly run stays quick. */
  static final int MAX_PATHS = 5000;

  /** Volatility (yearly %), correlations and path count. Missing values fall back to Kai's defaults. */
  public record Assumptions(double equityVol, double debtVol, double goldVol, double cashVol,
                            double equityDebt, double equityGold, double debtGold, int paths) {

    public static Assumptions from(JsonNode mc) {
      JsonNode v = mc == null ? null : mc.path("volatility");
      JsonNode c = mc == null ? null : mc.path("correlation");
      return new Assumptions(num(v, "equity", 18), num(v, "debt", 4), num(v, "gold", 15), num(v, "cash", 1),
          num(c, "equityDebt", 0.1), num(c, "equityGold", 0), num(c, "debtGold", 0.1),
          Math.max(200, Math.min(MAX_PATHS, mc == null ? 2000 : mc.path("paths").asInt(2000))));
    }

    private static double num(JsonNode n, String k, double d) {
      return n == null || !n.path(k).isNumber() ? d : n.path(k).asDouble();
    }
  }

  /** {@code successPct}: paths at or over the target, in percent; p10 and p50 of the corpus. */
  public record Result(double successPct, double p10, double p50, int paths) {}

  public static Result run(FundingStatusService.GoalInput g, double futureTarget, LocalDate today,
                           PlanConfig cfg, Assumptions a, long seed) {
    long months = Math.max(0, ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(g.targetDate())));
    int age = g.age() == null ? 35 : g.age();
    String risk = g.risk() == null ? "moderate" : g.risk();
    double start = g.currentValue() == null ? 0 : g.currentValue().doubleValue();
    double monthly = g.monthly() == null ? 0 : g.monthly().doubleValue();
    double step = g.stepUpPct() == null ? 0 : g.stepUpPct().doubleValue() / 100;

    // The weights and monthly drifts for each bucket, worked out once.
    String[] buckets = {"short", "mid", "long"};
    double[][] weights = new double[3][4];
    double[] mu = new double[4];
    double[] sd = {a.equityVol() / 100 / Math.sqrt(12), a.debtVol() / 100 / Math.sqrt(12),
        a.goldVol() / 100 / Math.sqrt(12), a.cashVol() / 100 / Math.sqrt(12)};
    String[] classes = {"equity", "debt", "gold", "cash"};
    for (int i = 0; i < 4; i++) {
      double r = cfg.returnPct(classes[i]).doubleValue() / 100;
      mu[i] = Math.log(1 + r) / 12 - sd[i] * sd[i] / 2;
    }
    for (int b = 0; b < 3; b++) {
      Map<String, java.math.BigDecimal> mix = FundingStatusService.mix(buckets[b], age, risk, cfg);
      double total = mix.values().stream().mapToDouble(java.math.BigDecimal::doubleValue).sum();
      for (int i = 0; i < 4; i++) {
        var w = mix.get(classes[i]);
        weights[b][i] = total <= 0 || w == null ? 0 : w.doubleValue() / total;
      }
    }
    // Cholesky of the equity, debt, gold correlation matrix.
    double l11 = 1;
    double l21 = a.equityDebt();
    double l22 = Math.sqrt(Math.max(1e-9, 1 - l21 * l21));
    double l31 = a.equityGold();
    double l32 = (a.debtGold() - l31 * l21) / l22;
    double l33 = Math.sqrt(Math.max(1e-9, 1 - l31 * l31 - l32 * l32));

    int[] bucketAt = new int[(int) months];
    for (int k = 0; k < months; k++) {
      String bk = cfg.bucketFor(months - k);
      bucketAt[k] = "short".equals(bk) ? 0 : "mid".equals(bk) ? 1 : 2;
    }
    SplittableRandom rnd = new SplittableRandom(seed);
    double[] end = new double[a.paths()];
    int hit = 0;
    for (int p = 0; p < a.paths(); p++) {
      double corpus = start;
      for (int k = 0; k < months; k++) {
        double z1 = gauss(rnd);
        double z2 = gauss(rnd);
        double z3 = gauss(rnd);
        double z4 = gauss(rnd);
        double e = l11 * z1;
        double d = l21 * z1 + l22 * z2;
        double gd = l31 * z1 + l32 * z2 + l33 * z3;
        double[] w = weights[bucketAt[k]];
        double growth = w[0] * Math.exp(mu[0] + sd[0] * e) + w[1] * Math.exp(mu[1] + sd[1] * d)
            + w[2] * Math.exp(mu[2] + sd[2] * gd) + w[3] * Math.exp(mu[3] + sd[3] * z4);
        double sumW = w[0] + w[1] + w[2] + w[3];
        if (sumW <= 0) growth = 1;
        else growth += 1 - sumW; // any unmapped share stays flat
        double contribution = monthly * Math.pow(1 + step, Math.floor(k / 12.0));
        corpus = (corpus + contribution) * growth;
      }
      end[p] = corpus;
      if (corpus >= futureTarget) hit++;
    }
    Arrays.sort(end);
    return new Result(100.0 * hit / a.paths(), end[(int) Math.floor(0.10 * (a.paths() - 1))],
        end[(int) Math.floor(0.50 * (a.paths() - 1))], a.paths());
  }

  /** A standard normal draw (Box-Muller; the second value is thrown away for simplicity). */
  private static double gauss(SplittableRandom r) {
    double u1 = 1 - r.nextDouble();
    double u2 = r.nextDouble();
    return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
  }
}
