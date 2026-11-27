package com.jizza.platform.usecase.vita.ranges;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator.Reading;
import com.jizza.platform.usecase.vita.ranges.TrendService.Point;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TargetEvaluatorTest {

  static final RedFlagRule LOW = RedFlagRule.fromConfig("low_sugar", Json.read("""
      {"kind": "glucose", "below": 70, "veryLowBelow": 54, "level": "urgent", "raisedTo": "emergency",
       "question": "Does the person need someone else's help to treat it?", "advice": "Act today."}"""), true);

  @Test
  void noRangeIsNotSetUnlessARedFlag() {
    var e = TargetEvaluator.evaluate(new Reading("glucose", BigDecimal.valueOf(150), null, "random", null), null, List.of(LOW), null);
    assertThat(e.status()).isEqualTo(TargetEvaluator.NOT_SET);
    assertThat(e.label()).isEqualTo("Not set: ask your doctor");
    var low = TargetEvaluator.evaluate(new Reading("glucose", BigDecimal.valueOf(52), null, "random", null), null, List.of(LOW), null);
    assertThat(low.status()).isEqualTo(TargetEvaluator.RED_FLAG);
    assertThat(low.redFlag().veryLow()).isTrue();
    assertThat(TargetEvaluator.evaluate(new Reading("glucose", BigDecimal.valueOf(70), null, "random", null), null, List.of(LOW), null)
        .redFlagged()).isFalse();
  }

  @Test
  void bandIncludesBothEndsAndAtMostIncludesItsBound() {
    Range band = Range.fromConfig(Json.read("{\"low\": 80, \"high\": 130, \"lowInclusive\": true, \"highInclusive\": true}"));
    assertThat(band.contains(BigDecimal.valueOf(80))).isTrue();
    assertThat(band.contains(BigDecimal.valueOf(130))).isTrue();
    assertThat(band.side(BigDecimal.valueOf(131))).isEqualTo("high");
    assertThat(band.side(BigDecimal.valueOf(79))).isEqualTo("low");
    assertThat(band.text()).isEqualTo("80–130");
    Range atMost = Range.fromConfig(Json.read("{\"atMost\": 7.0}"));
    assertThat(atMost.contains(new BigDecimal("7.0"))).isTrue();
    assertThat(atMost.contains(new BigDecimal("7.1"))).isFalse();
    assertThat(atMost.text()).isEqualTo("7.0 or lower");
  }

  @Test
  void aPairCountsOnceAsItsAverage() {
    UUID first = UUID.randomUUID();
    Instant t = Instant.parse("2026-12-01T02:00:00Z");
    var pts = List.of(new Point(first, t, BigDecimal.valueOf(150), BigDecimal.valueOf(95), first, null),
        new Point(UUID.randomUUID(), t.plusSeconds(60), BigDecimal.valueOf(140), BigDecimal.valueOf(85), first, null));
    var collapsed = TrendService.collapsePairs(pts);
    assertThat(collapsed).hasSize(1);
    assertThat(collapsed.get(0).value()).isEqualByComparingTo("145");
    assertThat(collapsed.get(0).value2()).isEqualByComparingTo("90");
  }
}
