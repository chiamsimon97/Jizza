package com.jizza.platform.usecase.finance.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.platform.usecase.finance.goal.FundingStatusService.Funding;
import com.jizza.platform.usecase.finance.goal.FundingStatusService.GoalInput;
import com.jizza.platform.usecase.finance.model.KaiDefaults;
import com.jizza.platform.usecase.finance.model.PlanConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FundingStatusServiceTest {

  static final PlanConfig CFG = KaiDefaults.plan();
  static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

  @Test
  void aShortGoalGrowsAtTheShortMixAndNeedsALevelSip() {
    // ₹1.5 lakh in 12 months, no inflation; ₹10,000 a month at 5.8% a year (70% cash at 5.5%,
    // 30% debt at 6.5%), paid at the start of each month.
    var in = new GoalInput(LocalDate.of(2027, 11, 1), bd(150_000), "none", BigDecimal.ZERO, bd(10_000),
        BigDecimal.ZERO, 34, "moderate");
    Funding f = FundingStatusService.fund(in, TODAY, CFG);
    assertThat(f.monthsToTarget()).isEqualTo(12);
    assertThat(f.bucket()).isEqualTo("short");
    assertThat(f.blendedReturnNow()).isEqualByComparingTo("5.8");
    assertThat(f.futureTarget()).isEqualByComparingTo("150000");
    assertThat(f.projected()).isEqualByComparingTo("123738");
    assertThat(f.fundedPct().setScale(1, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("82.5");
    assertThat(f.requiredSip()).isEqualByComparingTo("12200");

    // Paying the required SIP funds the goal; ₹100 less does not.
    var paid = FundingStatusService.fund(new GoalInput(in.targetDate(), in.targetToday(), "none",
        BigDecimal.ZERO, f.requiredSip(), BigDecimal.ZERO, 34, "moderate"), TODAY, CFG);
    assertThat(paid.fundedPct()).isGreaterThanOrEqualTo(BigDecimal.valueOf(100));
    var short100 = FundingStatusService.fund(new GoalInput(in.targetDate(), in.targetToday(), "none",
        BigDecimal.ZERO, f.requiredSip().subtract(bd(100)), BigDecimal.ZERO, 34, "moderate"), TODAY, CFG);
    assertThat(short100.fundedPct()).isLessThan(BigDecimal.valueOf(100));
    assertThat(paid.requiredSip()).isEqualByComparingTo("0");
  }

  @Test
  void theTargetGrowsWithInflationAndTheBucketMovesAsTheDateNears() {
    var in = new GoalInput(LocalDate.of(2036, 11, 1), bd(1_000_000), "cpi", BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, 34, "moderate");
    Funding f = FundingStatusService.fund(in, TODAY, CFG);
    assertThat(f.monthsToTarget()).isEqualTo(120);
    assertThat(f.bucket()).isEqualTo("long");
    // ₹10 lakh × 1.06^10
    assertThat(f.futureTarget()).isEqualByComparingTo("1790848");
    assertThat(f.nextBucket()).isEqualTo("mid");
    assertThat(f.monthsToNextBucket()).isEqualTo(37);
    assertThat(f.fundedPct()).isEqualByComparingTo("0");
  }

  @Test
  void weightedFundingCapsEachGoalAt100AndWeighsByFutureTarget() {
    Funding over = new Funding(0, "long", BigDecimal.ZERO, bd(3_000_000), bd(4_500_000), bd(150), BigDecimal.ZERO,
        BigDecimal.ZERO, java.util.Map.of(), null, null);
    Funding half = new Funding(0, "long", BigDecimal.ZERO, bd(1_000_000), bd(500_000), bd(50), BigDecimal.ZERO,
        BigDecimal.ZERO, java.util.Map.of(), null, null);
    // (100 × 30 lakh + 50 × 10 lakh) ÷ 40 lakh = 87.5
    assertThat(FundingStatusService.weighted(List.of(over, half))).isEqualByComparingTo("87.5");
    assertThat(FundingStatusService.weighted(List.of())).isNull();
  }

  static BigDecimal bd(double v) {
    return BigDecimal.valueOf(v);
  }
}
