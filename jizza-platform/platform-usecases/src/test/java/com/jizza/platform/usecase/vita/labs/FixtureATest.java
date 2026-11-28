package com.jizza.platform.usecase.vita.labs;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.vita.ranges.Range;
import com.jizza.platform.usecase.vita.ranges.RangeSet;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fixture A (spec section 10): Mother's HbA1c across three reports, every number exactly. The
 * ranges are the V66 config values, read the way the target service reads them.
 */
class FixtureATest {

  static final BigDecimal R1 = new BigDecimal("8.2");
  static final BigDecimal R2 = new BigDecimal("7.6");
  static final BigDecimal R3 = new BigDecimal("7.1");
  static final RangeSet HBA1C_DEFAULT = RangeSet.vitaDefault(Range.fromConfig(Json.read("{\"atMost\": 7.0}")), null,
      "clinical.ranges.diabetes_t2.hba1c", true);
  static final RangeSet BEFORE_MEAL = RangeSet.vitaDefault(
      Range.fromConfig(Json.read("{\"low\": 80, \"high\": 130, \"lowInclusive\": true, \"highInclusive\": true}")), null,
      "clinical.ranges.diabetes_t2.glucose_before_meal", true);

  private static String status(BigDecimal v, RangeSet r) {
    return TargetEvaluator.evaluate(new TargetEvaluator.Reading("lab", v, null, null, null), r, List.of(), null).status();
  }

  @Test
  void hba1cFallsOnePointOneFromR1ToR3() {
    var change = VitaLabUseCase.change(R1, R3, 1);
    assertThat(change.path("by").decimalValue()).isEqualByComparingTo("-1.1");
    assertThat(change.path("shown").decimalValue()).isEqualByComparingTo("-1.1");
    assertThat(change.path("direction").asText()).isEqualTo("down");
  }

  @Test
  void allThreeAreOutsideTheVitaDefaultAndNoneIsARedFlag() {
    for (BigDecimal v : List.of(R1, R2, R3)) assertThat(status(v, HBA1C_DEFAULT)).isEqualTo(TargetEvaluator.OUTSIDE_RANGE);
    var e = TargetEvaluator.evaluate(new TargetEvaluator.Reading("lab", R3, null, null, null), HBA1C_DEFAULT, List.of(), null);
    assertThat(e.range()).isEqualTo("7.0 or lower");
    assertThat(e.label()).isEqualTo("Vita default, needs verification");
    assertThat(e.redFlagged()).isFalse();
  }

  @Test
  void fastingAgainstEightyToOneThirty() {
    assertThat(status(new BigDecimal("142"), BEFORE_MEAL)).isEqualTo(TargetEvaluator.OUTSIDE_RANGE);
    assertThat(status(new BigDecimal("128"), BEFORE_MEAL)).isEqualTo(TargetEvaluator.IN_RANGE);
    assertThat(status(new BigDecimal("118"), BEFORE_MEAL)).isEqualTo(TargetEvaluator.IN_RANGE);
  }

  @Test
  void hba1cInMmolPerMolByTheNgspEquation() {
    var ngsp = LabSeed.normaliser().byCode("hba1c").orElseThrow().conversions().get(0);
    BigDecimal mmol = ngsp.invert(R3);
    assertThat(mmol.setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("54.09");
    assertThat(LabNormaliser.shown(mmol, 1)).isEqualByComparingTo("54.1");
    assertThat(ngsp.needsVerification()).isTrue();
  }

  @Test
  void estimatedAverageGlucoseFor7Point1() {
    BigDecimal eag = R3.multiply(new BigDecimal("28.7")).add(new BigDecimal("-46.7"));
    assertThat(eag).isEqualByComparingTo("157.07");
    assertThat(LabNormaliser.shown(eag, 0)).isEqualByComparingTo("157");
  }

  @Test
  void afterTheDoctorSetsSevenPointFiveR3IsInAndR2IsOut() {
    RangeSet doctor = RangeSet.doctor(Range.atMost(new BigDecimal("7.5")), null, UUID.randomUUID(), "Meera Rao",
        LocalDate.of(2026, 10, 1));
    assertThat(status(R3, doctor)).isEqualTo(TargetEvaluator.IN_RANGE);
    assertThat(status(R2, doctor)).isEqualTo(TargetEvaluator.OUTSIDE_RANGE);
    assertThat(doctor.label()).isEqualTo("from Dr Meera Rao, set on 1 Oct 2026");
  }
}
