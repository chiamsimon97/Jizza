package com.jizza.platform.usecase.vita.ranges;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator.Evaluation;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator.Reading;
import com.jizza.platform.usecase.vita.ranges.TrendService.Point;
import com.jizza.platform.usecase.vita.ranges.TrendService.Trend;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Fixture B of spec section 10: Father's home BP over 7 days under a doctor's target (B1) and under
 * the Vita default (B2), then a red flag. Every number is the fixture's, asserted exactly.
 */
class FixtureBTest {

  static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  static final int[] MS = {148, 152, 138, 144, 160, 136, 142};
  static final int[] MD = {92, 94, 88, 90, 98, 86, 90};
  static final int[] ES = {140, 146, 134, 138, 150, 132, 140};
  static final int[] ED = {88, 90, 84, 86, 94, 82, 88};

  /** The red-flag rule and home default exactly as V66 seeds them (with V67's wording fix). */
  static final JsonNode BP_RULE = Json.read("""
      {"kind": "bp", "unit": "mmHg", "systolicAbove": 180, "diastolicAbove": 120,
       "level": "urgent", "raisedTo": "emergency", "question": "Any of these now?",
       "signs": ["chest pain"], "advice": "Wait five minutes and measure again.",
       "emergencyAdvice": "Call the emergency number now."}""");
  static final JsonNode HOME_DEFAULT = Json.read("""
      {"kind": "bp", "setting": "home", "unit": "mmHg", "systolic": {"below": 135}, "diastolic": {"below": 85}}""");

  static final UUID IYER = UUID.randomUUID();
  static final RangeSet B1 = RangeSet.doctor(Range.below(bd(140)), Range.below(bd(90)), IYER, "S Iyer",
      LocalDate.of(2026, 11, 1));
  static final RangeSet B2 = RangeSet.vitaDefault(Range.fromConfig(HOME_DEFAULT.get("systolic")),
      Range.fromConfig(HOME_DEFAULT.get("diastolic")), "clinical.ranges.general.bp_home", true);
  static final List<RedFlagRule> RULES = List.of(RedFlagRule.fromConfig("bp", BP_RULE, true));

  static BigDecimal bd(int v) {
    return BigDecimal.valueOf(v);
  }

  static Evaluation eval(RangeSet range, int sys, int dia) {
    return TargetEvaluator.evaluate(new Reading("bp", bd(sys), bd(dia), null, "home"), range, RULES, null);
  }

  /** Day k (1-based) morning at 07:30, evening at 19:30, India time. */
  static List<Point> points(RangeSet range) {
    List<Point> out = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      LocalDateTime day = LocalDateTime.of(2026, 12, 1 + d, 7, 30);
      out.add(new Point(UUID.randomUUID(), day.atZone(ZONE).toInstant(), bd(MS[d]), bd(MD[d]), null,
          eval(range, MS[d], MD[d]).status()));
      out.add(new Point(UUID.randomUUID(), day.withHour(19).atZone(ZONE).toInstant(), bd(ES[d]), bd(ED[d]), null,
          eval(range, ES[d], ED[d]).status()));
    }
    return out;
  }

  @Test
  void b1MorningEveningAndAllDayAverages() {
    Trend t = TrendService.trend("bp", points(B1), ZONE);
    assertThat(t.morning().value().sum()).isEqualByComparingTo("1020");
    assertThat(t.morning().value().mean().setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("145.71");
    assertThat(t.morning().value().shown()).isEqualByComparingTo("146");
    assertThat(t.morning().value2().sum()).isEqualByComparingTo("638");
    assertThat(t.morning().value2().mean().setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("91.14");
    assertThat(t.morning().value2().shown()).isEqualByComparingTo("91");
    assertThat(B1.value().contains(bd(146)) && B1.value2().contains(bd(91))).isFalse();

    assertThat(t.evening().value().sum()).isEqualByComparingTo("980");
    assertThat(t.evening().value().mean()).isEqualByComparingTo("140.00");
    assertThat(t.evening().value().shown()).isEqualByComparingTo("140");
    assertThat(t.evening().value2().sum()).isEqualByComparingTo("612");
    assertThat(t.evening().value2().mean().setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("87.43");
    assertThat(t.evening().value2().shown()).isEqualByComparingTo("87");
    // 140/87 is outside: 140 is not below 140.
    assertThat(B1.value().contains(bd(140))).isFalse();

    assertThat(t.all().count()).isEqualTo(14);
    assertThat(t.all().value().sum()).isEqualByComparingTo("2000");
    assertThat(t.all().value().mean().setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("142.86");
    assertThat(t.all().value().shown()).isEqualByComparingTo("143");
    assertThat(t.all().value2().sum()).isEqualByComparingTo("1250");
    assertThat(t.all().value2().mean().setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("89.29");
    assertThat(t.all().value2().shown()).isEqualByComparingTo("89");
    assertThat(t.days()).hasSize(7);
  }

  @Test
  void b1DaysInAndOutOfRangeAndTheLabel() {
    List<Integer> morningIn = IntStream.rangeClosed(1, 7)
        .filter(d -> eval(B1, MS[d - 1], MD[d - 1]).status().equals(TargetEvaluator.IN_RANGE)).boxed().toList();
    List<Integer> eveningIn = IntStream.rangeClosed(1, 7)
        .filter(d -> eval(B1, ES[d - 1], ED[d - 1]).status().equals(TargetEvaluator.IN_RANGE)).boxed().toList();
    assertThat(morningIn).containsExactly(3, 6);
    assertThat(eveningIn).containsExactly(3, 4, 6);
    // Evening 1 (140/88), 2 (146/90), 5, 7 (140/88) are outside.
    assertThat(eval(B1, 140, 88).outside()).containsExactly("systolic high");
    assertThat(eval(B1, 146, 90).outside()).containsExactly("systolic high", "diastolic high");

    for (int d = 0; d < 7; d++) {
      Evaluation e = eval(B1, MS[d], MD[d]);
      assertThat(e.label()).startsWith("from Dr S Iyer");
      assertThat(e.rangeSource()).isEqualTo(RangeSet.DOCTOR);
      assertThat(e.needsVerification()).isFalse();
    }
    assertThat(eval(B1, 150, 95).label()).isEqualTo("from Dr S Iyer, set on 1 Nov 2026");
    assertThat(eval(B1, 150, 95).range()).isEqualTo("below 140/90");
    assertThat(TrendService.trend("bp", points(B1), ZONE).all().outside()).isEqualTo(14 - 5);
  }

  @Test
  void boundaryRows() {
    assertThat(eval(B1, 140, 88).status()).isEqualTo(TargetEvaluator.OUTSIDE_RANGE);
    assertThat(eval(B1, 139, 90).status()).isEqualTo(TargetEvaluator.OUTSIDE_RANGE);
    assertThat(eval(B1, 139, 89).status()).isEqualTo(TargetEvaluator.IN_RANGE);
  }

  @Test
  void b2VitaDefaultTwelveOfFourteenOutside() {
    List<Point> pts = points(B2);
    long outside = pts.stream().filter(p -> p.status().equals(TargetEvaluator.OUTSIDE_RANGE)).count();
    assertThat(outside).isEqualTo(12);
    assertThat(pts.stream().filter(p -> p.status().equals(TargetEvaluator.IN_RANGE))
        .map(p -> p.value().intValue() + "/" + p.value2().intValue()).toList())
        .containsExactly("134/84", "132/82");
    Evaluation e = eval(B2, 148, 92);
    assertThat(e.label()).isEqualTo("Vita default, needs verification");
    assertThat(e.needsVerification()).isTrue();
    assertThat(e.range()).isEqualTo("below 135/85");
  }

  @Test
  void redFlagAt182Over112IsUrgentThenEmergencyOnAYes() {
    Evaluation e = eval(B1, 182, 112);
    assertThat(e.status()).isEqualTo(TargetEvaluator.RED_FLAG);
    assertThat(e.redFlag().level()).isEqualTo(RedFlagRule.URGENT);
    assertThat(e.redFlag().ruleId()).isEqualTo("bp");
    assertThat(e.redFlag().question()).isEqualTo("Any of these now?");
    assertThat(e.redFlag().advice()).contains("five minutes");
    assertThat(e.redFlag().needsVerification()).isTrue();

    Evaluation yes = TargetEvaluator.evaluate(new Reading("bp", bd(182), bd(112), null, "home"), B1, RULES, true);
    assertThat(yes.redFlag().level()).isEqualTo(RedFlagRule.EMERGENCY);
    assertThat(yes.redFlag().advice()).isEqualTo("Call the emergency number now.");

    // "Above" excludes the bound: 180/120 is not a red flag.
    assertThat(eval(B1, 180, 120).redFlagged()).isFalse();
    assertThat(eval(B1, 180, 121).redFlagged()).isTrue();
  }
}
