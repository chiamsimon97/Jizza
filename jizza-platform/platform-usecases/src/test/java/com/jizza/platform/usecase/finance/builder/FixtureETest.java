package com.jizza.platform.usecase.finance.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.Edit;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.GoalNeed;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.Input;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.Line;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.Needs;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.Result;
import com.jizza.platform.usecase.finance.model.KaiDefaults;
import com.jizza.platform.usecase.finance.model.PlanConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fixture E of the spec: the plan builder's split for age 34, Moderate, no goals, emergency fund
 * full, no loan above 9%. Monthly ₹50,000 and a lumpsum of ₹3,00,000, on Kai's shipped defaults.
 */
class FixtureETest {

  static final PlanConfig CFG = KaiDefaults.plan();

  static Result split(int age, List<Edit> edits) {
    return AllocationResolver.resolve(new Input(bd(50_000), bd(300_000), age, "moderate", Needs.none(),
        List.of(), edits), CFG);
  }

  @Test
  void everyMonthlyLeafOfTheSplit() {
    Result r = split(34, List.of());
    assertLine(r, "short.cash.liquid", 7_000);
    assertLine(r, "short.debt.arbitrage_ultra_short", 3_000);
    assertLine(r, "mid.equity.balanced_multi_asset", 7_500);
    assertLine(r, "mid.debt.short_duration", 6_000);
    assertLine(r, "mid.gold.gold", 1_500);
    assertLine(r, "long.equity.large_cap_index", 8_700);
    assertLine(r, "long.equity.flexi_cap", 6_100);
    assertLine(r, "long.equity.mid_cap", 1_800);
    assertLine(r, "long.equity.small_cap", 900);
    assertLine(r, "long.debt.ppf_debt", 5_000);
    assertLine(r, "long.gold.gold", 2_500);
    assertThat(r.lines()).hasSize(11);

    assertThat(r.node("short").monthly()).isEqualByComparingTo("10000");
    assertThat(r.node("mid").monthly()).isEqualByComparingTo("15000");
    assertThat(r.node("long").monthly()).isEqualByComparingTo("25000");
    assertThat(r.node("long.equity").monthly()).isEqualByComparingTo("17500");
    assertThat(r.lines().stream().map(Line::monthly).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("50000");
    assertThat(r.offTheTop()).isEmpty();
    assertThat(r.node("short").sourceLabel()).isEqualTo("Age 30–39");
    assertThat(r.node("long.equity.mid_cap").sourceLabel()).isEqualTo("Moderate");
  }

  @Test
  void lumpsumInvestsShortAndMidTodayAndMovesLongEquityByStp() {
    Result r = split(34, List.of());
    assertThat(r.node("short").lumpsum()).isEqualByComparingTo("60000");
    assertThat(r.node("short.cash").lumpsum()).isEqualByComparingTo("42000");
    assertThat(r.node("short.debt").lumpsum()).isEqualByComparingTo("18000");
    assertThat(r.node("mid").lumpsum()).isEqualByComparingTo("90000");
    assertThat(r.node("mid.equity").lumpsum()).isEqualByComparingTo("45000");
    assertThat(r.node("mid.debt").lumpsum()).isEqualByComparingTo("36000");
    assertThat(r.node("mid.gold").lumpsum()).isEqualByComparingTo("9000");
    assertThat(r.node("long").lumpsum()).isEqualByComparingTo("150000");
    assertThat(r.node("long.debt").lumpsum()).isEqualByComparingTo("30000");
    assertThat(r.node("long.gold").lumpsum()).isEqualByComparingTo("15000");

    var t = r.timeline();
    assertThat(t.parked()).isEqualByComparingTo("105000");
    assertThat(t.investedToday()).isEqualByComparingTo("195000"); // 60,000 + 90,000 + 30,000 + 15,000
    assertThat(t.stpMonths()).isEqualTo(6);
    assertThat(t.stp()).hasSize(6).allSatisfy(row -> assertThat(row.amount()).isEqualByComparingTo("17500"));
  }

  @Test
  void atTheFortiethBirthdayBothTheHorizonSplitAndTheLongRowChange() {
    Result r = split(40, List.of());
    assertThat(r.node("short").monthly()).isEqualByComparingTo("10000"); // 20%
    assertThat(r.node("mid").monthly()).isEqualByComparingTo("17500"); // 35%
    assertThat(r.node("long").monthly()).isEqualByComparingTo("22500"); // 45%
    // The 40–49 row (60 / 30 / 10) of ₹22,500: 13,500 / 6,750 / 2,250, rounded half up to ₹100
    // (6,800 and 2,300) with the ₹100 excess taken from the largest part.
    assertThat(r.node("long.equity").monthly()).isEqualByComparingTo("13400");
    assertThat(r.node("long.debt").monthly()).isEqualByComparingTo("6800");
    assertThat(r.node("long.gold").monthly()).isEqualByComparingTo("2300");
    assertThat(r.node("long").sourceLabel()).isEqualTo("Age 40–49");
  }

  @Test
  void editingMidCapMarksItYoursAndKeepsLongEquityAt17500() {
    Result r = split(34, List.of(new Edit("long.equity.mid_cap", bd(3_000))));
    assertLine(r, "long.equity.large_cap_index", 8_100);
    assertLine(r, "long.equity.flexi_cap", 5_600);
    assertLine(r, "long.equity.mid_cap", 3_000);
    assertLine(r, "long.equity.small_cap", 800);
    assertThat(r.line("long.equity.mid_cap").source()).isEqualTo("user");
    assertThat(r.line("long.equity.mid_cap").sourceLabel()).isEqualTo("You");
    assertThat(r.node("long.equity").monthly()).isEqualByComparingTo("17500");
    assertThat(r.lines().stream().map(Line::monthly).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("50000");
  }

  @Test
  void survivalNeedsComeOffTheTopFirst() {
    // Starter gap ₹40,000; ₹1.5 lakh at 14%; ₹2 lakh more to reach 6 months.
    var needs = new Needs(bd(40_000), bd(200_000), bd(150_000), bd(12));
    Result r = AllocationResolver.resolve(new Input(bd(50_000), BigDecimal.ZERO, 34, "moderate", needs,
        List.of(), List.of()), CFG);
    assertThat(r.offTheTop()).extracting(o -> o.key()).containsExactly("starter_buffer", "high_interest_debt");
    assertThat(r.offTheTop().get(0).monthly()).isEqualByComparingTo("40000");
    assertThat(r.offTheTop().get(1).monthly()).isEqualByComparingTo("10000");
    assertThat(r.monthlyInvested()).isEqualByComparingTo("0");

    var noStarter = new Needs(BigDecimal.ZERO, bd(200_000), BigDecimal.ZERO, bd(12));
    Result half = AllocationResolver.resolve(new Input(bd(50_000), BigDecimal.ZERO, 34, "moderate", noStarter,
        List.of(), List.of()), CFG);
    assertThat(half.offTheTop()).singleElement().satisfies(o -> {
      assertThat(o.key()).isEqualTo("emergency_fund");
      assertThat(o.monthly()).isEqualByComparingTo("25000");
    });
    assertThat(half.monthlyInvested()).isEqualByComparingTo("25000");
  }

  @Test
  void goalsTakeTheirRequiredSipInTheirBucketFirst() {
    var home = new GoalNeed("g1", "Home", "mid", "important", LocalDate.of(2029, 6, 1), bd(12_000));
    Result r = AllocationResolver.resolve(new Input(bd(50_000), BigDecimal.ZERO, 34, "moderate", Needs.none(),
        List.of(home), List.of()), CFG);
    // The other ₹38,000 follows the age table: 7,600 / 11,400 / 19,000.
    assertThat(r.node("mid").monthly()).isEqualByComparingTo("23400");
    assertThat(r.node("mid").source()).isEqualTo("goal");
    assertThat(r.node("mid").sourceLabel()).isEqualTo("Goal: Home 2029");
    assertThat(r.node("short").monthly()).isEqualByComparingTo("7600");
    assertThat(r.node("long").monthly()).isEqualByComparingTo("19000");
    assertThat(r.goalLens()).anySatisfy(s -> {
      assertThat(s.goalId()).isEqualTo("g1");
      assertThat(s.monthly()).isEqualByComparingTo("12000");
    });
  }

  @Test
  void amountsSetByHandCannotExceedTheirParent() {
    assertThatThrownBy(() -> split(34, List.of(new Edit("long.equity.mid_cap", bd(20_000)))))
        .isInstanceOf(ValidationException.class).hasMessageContaining("more than");
    assertThatThrownBy(() -> split(34, List.of(new Edit("long.equity.crypto", bd(100)))))
        .isInstanceOf(ValidationException.class).hasMessageContaining("no part called");
  }

  @Test
  void roundingPushesTheRemainderToTheLargestPart() {
    var m = AllocationResolver.distribute(bd(17_500), List.of("a", "b", "c", "d"),
        java.util.Map.of("a", bd(50), "b", bd(35), "c", bd(10), "d", bd(5)), java.util.Map.of(), "x");
    assertThat(m.get("a")).isEqualByComparingTo("8700");
    assertThat(m.get("b")).isEqualByComparingTo("6100");
    assertThat(m.get("c")).isEqualByComparingTo("1800");
    assertThat(m.get("d")).isEqualByComparingTo("900");
  }

  private static void assertLine(Result r, String key, long amount) {
    Line l = r.line(key);
    assertThat(l).as(key).isNotNull();
    assertThat(l.monthly()).as(key).isEqualByComparingTo(BigDecimal.valueOf(amount));
  }

  static BigDecimal bd(double v) {
    return BigDecimal.valueOf(v);
  }
}
