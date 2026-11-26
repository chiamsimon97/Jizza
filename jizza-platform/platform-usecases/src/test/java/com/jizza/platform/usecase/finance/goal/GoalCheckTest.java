package com.jizza.platform.usecase.finance.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.platform.usecase.finance.goal.FundingStatusService.GoalInput;
import com.jizza.platform.usecase.finance.model.KaiDefaults;
import com.jizza.platform.usecase.finance.model.PlanConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class GoalCheckTest {

  static final PlanConfig CFG = KaiDefaults.plan();
  static final LocalDate TODAY = LocalDate.of(2026, 11, 26);
  static final GoalSimulation.Assumptions A = GoalSimulation.Assumptions.from(null);

  @Test
  void theVerdictComparesTheChanceWithTheBarAndTheMargin() {
    BigDecimal some = BigDecimal.valueOf(1000);
    assertThat(GoalCheckService.verdict(80, 75, 15, 0, some, some)).isEqualTo(GoalCheckService.ON_TRACK);
    assertThat(GoalCheckService.verdict(80, 75, 15, 1, some, some)).isEqualTo(GoalCheckService.AT_RISK);
    assertThat(GoalCheckService.verdict(60, 75, 15, 0, some, some)).isEqualTo(GoalCheckService.AT_RISK);
    assertThat(GoalCheckService.verdict(59.9, 75, 15, 0, some, some)).isEqualTo(GoalCheckService.OFF_TRACK);
    assertThat(GoalCheckService.verdict(99, 75, 15, 0, BigDecimal.ZERO, BigDecimal.ZERO))
        .isEqualTo(GoalCheckService.OFF_TRACK);
  }

  @Test
  void theSimulationIsTheSameForTheSameSeedAndRisesWithTheMonthlyAmount() {
    GoalInput low = input(10_000);
    var f = FundingStatusService.fund(low, TODAY, CFG);
    double target = f.futureTarget().doubleValue();
    var a = GoalSimulation.run(low, target, TODAY, CFG, A, 42);
    var b = GoalSimulation.run(low, target, TODAY, CFG, A, 42);
    assertThat(a).isEqualTo(b);
    assertThat(a.paths()).isEqualTo(2000);
    assertThat(a.p10()).isLessThan(a.p50());

    // At the monthly amount the plan asks for, about half the paths get there; well above it, most do.
    int required = f.requiredSip().intValue();
    var atPlan = GoalSimulation.run(input(required), target, TODAY, CFG, A, 42);
    var high = GoalSimulation.run(input(required * 3 / 2), target, TODAY, CFG, A, 42);
    assertThat(atPlan.successPct()).isGreaterThan(a.successPct()).isBetween(35.0, 70.0);
    assertThat(high.successPct()).isGreaterThan(90);
  }

  @Test
  void theMedianPathIsCloseToTheDeterministicProjection() {
    GoalInput in = input(20_000);
    var f = FundingStatusService.fund(in, TODAY, CFG);
    var sim = GoalSimulation.run(in, f.futureTarget().doubleValue(), TODAY, CFG, A, 7);
    double ratio = sim.p50() / f.projected().doubleValue();
    assertThat(ratio).isBetween(0.85, 1.10);
  }

  @Test
  void pathsAreKeptWithinBounds() {
    var many = GoalSimulation.Assumptions.from(com.jizza.engine.api.util.Json.obj().put("paths", 1_000_000));
    assertThat(many.paths()).isEqualTo(GoalSimulation.MAX_PATHS);
    var few = GoalSimulation.Assumptions.from(com.jizza.engine.api.util.Json.obj().put("paths", 3));
    assertThat(few.paths()).isEqualTo(200);
  }

  @Test
  void theFixNamesTheGapAndTheMix() {
    String fix = GoalCheckService.fix(GoalCheckService.OFF_TRACK, BigDecimal.valueOf(5000), BigDecimal.valueOf(300000),
        2, 1, BigDecimal.ONE, BigDecimal.ONE);
    assertThat(fix).startsWith("Short by about ₹3,00,000; ₹5,000 more a month").contains("2 parts of its mix").contains("direct shares");
    assertThat(GoalCheckService.fix(GoalCheckService.ON_TRACK, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0,
        BigDecimal.ONE, BigDecimal.ONE)).isEqualTo("Keep going as you are.");
  }

  @Test
  void amountsInTheFixAreRoundedUp() {
    assertThat(GoalCheckService.roundTo(BigDecimal.valueOf(9_621_838), 1000)).isEqualByComparingTo("9622000");
    assertThat(GoalCheckService.roundTo(BigDecimal.valueOf(2_651), 100)).isEqualByComparingTo("2700");
  }

  private static GoalInput input(int monthly) {
    return new GoalInput(LocalDate.of(2036, 12, 1), BigDecimal.valueOf(5_000_000), "cpi", BigDecimal.valueOf(200_000),
        BigDecimal.valueOf(monthly), BigDecimal.ZERO, 34, "moderate");
  }
}
