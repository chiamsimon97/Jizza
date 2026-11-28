package com.jizza.platform.usecase.vita.labs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fixture D (spec section 10), the lab half: the six reviewed rows of the page become results with
 * their codes, the LDL conversion with its range, the grouped counts, and three lab flags high.
 */
class FixtureDLabTest {

  static final String ROWS = """
      [{"name": "HbA1c", "valueAsPrinted": "7.1", "value": 7.1, "unit": "%", "rangeAsPrinted": "4.0-5.6"},
       {"name": "Glucose Fasting (FBS)", "valueAsPrinted": "118", "value": 118, "unit": "mg/dL", "rangeAsPrinted": "70-100"},
       {"name": "Serum Creatinine", "valueAsPrinted": "0,9", "value": 0.9, "unit": "mg/dL", "rangeAsPrinted": "0.6-1.1", "confidence": 0.4},
       {"name": "LDL Cholesterol", "valueAsPrinted": "3.1", "value": 3.1, "unit": "mmol/L", "rangeAsPrinted": "<2.6"},
       {"name": "Total Leucocyte Count", "valueAsPrinted": "7,500", "unit": "/cumm", "rangeAsPrinted": "4,000-11,000"},
       {"name": "Platelet Count", "valueAsPrinted": "2,50,000", "unit": "/cumm", "rangeAsPrinted": "1,50,000-4,10,000"}]""";

  private List<ObjectNode> results() {
    List<ObjectNode> out = new ArrayList<>();
    for (var row : Json.read(ROWS)) out.add(VitaLabUseCase.resultValues(row, LabSeed.normaliser()));
    return out;
  }

  @Test
  void sixRowsWithTheirTests() {
    var r = results();
    assertThat(r).hasSize(6);
    assertThat(r.stream().map(x -> x.path("testCode").asText()).toList())
        .containsExactly("hba1c", "fasting_glucose", "creatinine", "ldl", "tlc", "platelets");
  }

  @Test
  void theLdlIsConvertedWithItsRangeAndThePrintedUnitKept() {
    var ldl = results().get(3);
    assertThat(ldl.path("unit").asText()).isEqualTo("mmol/L");
    assertThat(ldl.path("unitCanonical").asText()).isEqualTo("mg/dL");
    assertThat(ldl.path("valueCanonical").decimalValue()).isEqualByComparingTo("119.877");
    assertThat(LabNormaliser.shown(ldl.path("valueCanonical").decimalValue(), 0)).isEqualByComparingTo("120");
    assertThat(ldl.path("refHighCanonical").decimalValue()).isEqualByComparingTo("100.542");
    assertThat(ldl.path("refHighInclusive").asBoolean()).isFalse();
    assertThat(LabNormaliser.rangeShown(null, true, ldl.path("refHighCanonical").decimalValue(), false, 0)).isEqualTo("<101");
    assertThat(ldl.path("conversion").path("needsVerification").asBoolean()).isTrue();
  }

  @Test
  void theGroupedCountsAndTheDecimalComma() {
    var r = results();
    assertThat(r.get(4).path("valueNum").decimalValue()).isEqualByComparingTo("7500");
    assertThat(r.get(4).path("refLow").decimalValue()).isEqualByComparingTo("4000");
    assertThat(r.get(4).path("refHigh").decimalValue()).isEqualByComparingTo("11000");
    assertThat(r.get(5).path("valueNum").decimalValue()).isEqualByComparingTo("250000");
    assertThat(r.get(5).path("refLow").decimalValue()).isEqualByComparingTo("150000");
    assertThat(r.get(5).path("refHigh").decimalValue()).isEqualByComparingTo("410000");
    assertThat(r.get(2).path("valueNum").decimalValue()).isEqualByComparingTo("0.9");
    assertThat(r.get(2).path("confidence").decimalValue()).isEqualByComparingTo("0.40");
  }

  @Test
  void threeLabFlagsHighAndNoneCritical() {
    assertThat(results().stream().map(x -> x.path("flag").asText()).toList())
        .containsExactly("high", "high", "normal", "high", "normal", "normal");
  }

  @Test
  void criticalOnlyWhenTheLabPrintedIt() {
    var printed = VitaLabUseCase.resultValues(Json.read(
        "{\"name\": \"Potassium\", \"valueAsPrinted\": \"6.9\", \"rangeAsPrinted\": \"3.5-5.1\", \"flagAsPrinted\": \"Critical\"}"),
        LabSeed.normaliser());
    assertThat(printed.path("flag").asText()).isEqualTo("critical");
    var edited = VitaLabUseCase.resultValues(Json.read(
        "{\"name\": \"Potassium\", \"valueAsPrinted\": \"6.9\", \"rangeAsPrinted\": \"3.5-5.1\", \"flag\": \"critical\"}"),
        LabSeed.normaliser());
    assertThat(edited.path("flag").asText()).isEqualTo("high");
    assertThat(edited.path("testCode").isNull()).isTrue();
  }

  @Test
  void aBandedRangeIsKeptWholeAndFlagsNothing() {
    var r = VitaLabUseCase.resultValues(Json.read("""
        {"name": "LDL Cholesterol", "valueAsPrinted": "121.4", "unit": "mg/dL",
         "rangeAsPrinted": "Optimal: < 100; Near Optimal: 100-129; Borderline high: 130-159"}"""), LabSeed.normaliser());
    assertThat(r.path("refText").asText()).startsWith("Optimal: < 100;");
    assertThat(r.path("flag").asText()).isEqualTo("unknown");
    assertThat(r.path("valueCanonical").decimalValue()).isEqualByComparingTo("121.4");
  }
}
