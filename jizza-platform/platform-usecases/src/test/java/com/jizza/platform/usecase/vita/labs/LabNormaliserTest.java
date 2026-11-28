package com.jizza.platform.usecase.vita.labs;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaStore.LabTestRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The lab normaliser (spec 5.4): names, units, conversions with a source only, unknown tests. */
class LabNormaliserTest {

  private final LabNormaliser n = LabSeed.normaliser();

  private String code(String printed) {
    return n.test(printed).map(LabNormaliser.Test::code).orElse(null);
  }

  @Test
  void printedNamesFromTheSamplesFindTheirTest() {
    Map<String, String> names = Map.ofEntries(
        Map.entry("Glucose Fasting (FBS)", "fasting_glucose"), Map.entry("Glucose, Fasting", "fasting_glucose"),
        Map.entry("Glucose fasting", "fasting_glucose"), Map.entry("FBS", "fasting_glucose"),
        Map.entry("HbA1C- Glycated Haemoglobin", "hba1c"), Map.entry("HbA1c", "hba1c"),
        Map.entry("Cholesterol, Total", "total_cholesterol"), Map.entry("Cholesterol-Total", "total_cholesterol"),
        Map.entry("LDL Cholesterol, Calculated", "ldl"), Map.entry("VLDL Cholesterol,Calculated", "vldl"),
        Map.entry("Non-HDL Cholesterol", "non_hdl"), Map.entry("HDL Cholesterol", "hdl"),
        Map.entry("Serum Creatinine", "creatinine"), Map.entry("GFR Estimated", "egfr"), Map.entry("Urea", "urea"),
        Map.entry("TSH(Ultrasensitive)", "tsh"), Map.entry("Haemoglobin (Hb)", "haemoglobin"),
        Map.entry("Total Leucocyte Count", "tlc"), Map.entry("Platelet Count", "platelets"));
    names.forEach((printed, expected) -> assertThat(code(printed)).as(printed).isEqualTo(expected));
    // Glycated haemoglobin is HbA1c, never plain haemoglobin.
    assertThat(code("Glycated Haemoglobin")).isEqualTo("hba1c");
  }

  @Test
  void anUnknownTestIsKeptAsPrinted() {
    assertThat(code("Estimated average glucose (eAG)")).isNull();
    var r = n.normalise("Estimated average glucose (eAG)", "mg/dL", new BigDecimal("97"), null, null);
    assertThat(r.unitStatus()).isEqualTo(LabNormaliser.UNKNOWN_TEST);
    assertThat(r.valueCanonical()).isNull();
  }

  @Test
  void spellingsOfTheSameUnitAreTheCanonicalUnit() {
    assertThat(n.normalise("Glucose Fasting", "mg/dl", new BigDecimal("100"), null, null).unitStatus()).isEqualTo(LabNormaliser.CANONICAL);
    assertThat(n.normalise("Glucose Fasting", "mg%", new BigDecimal("100"), null, null).valueCanonical()).isEqualByComparingTo("100");
    assertThat(n.normalise("Haemoglobin (Hb)", "gm%", new BigDecimal("12.9"), null, null).unitCanonical()).isEqualTo("g/dL");
    assertThat(n.normalise("TSH", "μIU/mL", new BigDecimal("2.03"), null, null).unitStatus()).isEqualTo(LabNormaliser.CANONICAL);
  }

  @Test
  void aConversionWithoutASourceIsNeverUsed() {
    // Glucose has no conversion in the seed: a value in mmol/L stays as printed.
    var glucose = n.normalise("Glucose Fasting", "mmol/L", new BigDecimal("6.5"), new BigDecimal("3.9"), new BigDecimal("5.5"));
    assertThat(glucose.unitStatus()).isEqualTo(LabNormaliser.NOT_CONVERTED);
    assertThat(glucose.valueCanonical()).isNull();
    assertThat(glucose.refHighCanonical()).isNull();
    // A conversion row with no source is dropped when the table is read.
    var noSource = new LabNormaliser(List.of(new LabTestRow("x", "X", List.of("X"), "mg/dL", List.of(), 0,
        Json.read("[{\"from\": \"mmol/L\", \"multiply\": 18}]"), "other", 0)));
    assertThat(noSource.normalise("X", "mmol/L", BigDecimal.ONE, null, null).unitStatus()).isEqualTo(LabNormaliser.NOT_CONVERTED);
  }

  @Test
  void theRangeConvertsWithTheValueAndIsShownRounded() {
    var ldl = n.normalise("LDL Cholesterol", "mmol/L", new BigDecimal("3.1"), null, new BigDecimal("2.6"));
    assertThat(ldl.unitStatus()).isEqualTo(LabNormaliser.CONVERTED);
    assertThat(ldl.conversion().needsVerification()).isTrue();
    assertThat(ldl.valueCanonical()).isEqualByComparingTo("119.877");
    assertThat(ldl.refHighCanonical()).isEqualByComparingTo("100.542");
    assertThat(LabNormaliser.shown(ldl.valueCanonical(), 0)).isEqualByComparingTo("120");
    assertThat(LabNormaliser.rangeShown(null, true, ldl.refHighCanonical(), false, 0)).isEqualTo("<101");
    assertThat(LabNormaliser.rangeShown(new BigDecimal("4000"), true, new BigDecimal("11000"), true, 0)).isEqualTo("4000–11000");
    assertThat(LabNormaliser.rangeShown(new BigDecimal("40"), false, null, true, 0)).isEqualTo(">40");
  }
}
