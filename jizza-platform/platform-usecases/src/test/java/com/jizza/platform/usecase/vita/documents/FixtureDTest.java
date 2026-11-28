package com.jizza.platform.usecase.vita.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fixture D of spec section 10, the parts step 7a produces: six rows, the collection date, the
 * patient check for Mother and Father, the comma cases and the lab's flags. The test-name mapping
 * (FBS to fasting glucose) and the LDL conversion are the lab normaliser's (step 7b).
 */
class FixtureDTest {

  /** What the model copies from the page, exactly as printed. */
  static final JsonNode PAGE = Json.read("""
      {"kind": "lab_report", "documentDate": "15/09/2026", "labName": "Fictional Path Lab",
       "patients": [{"name": "", "age": "62", "sex": "F"}],
       "tests": [
         {"name": "HbA1c", "value": "7.1", "unit": "%", "range": "4.0-5.6", "flag": ""},
         {"name": "Glucose Fasting (FBS)", "value": "118", "unit": "mg/dL", "range": "70-100"},
         {"name": "Serum Creatinine", "value": "0,9", "unit": "mg/dL", "range": "0.6-1.1"},
         {"name": "LDL Cholesterol", "value": "3.1", "unit": "mmol/L", "range": "<2.6"},
         {"name": "Total Leucocyte Count", "value": "7,500", "unit": "/cumm", "range": "4,000-11,000"},
         {"name": "Platelet Count", "value": "2,50,000", "unit": "/cumm", "range": "1,50,000-4,10,000"}]}""");

  static final VitaExtractionService.PersonFacts MOTHER =
      new VitaExtractionService.PersonFacts("Mother", LocalDate.of(1964, 2, 10), "female");
  static final VitaExtractionService.PersonFacts FATHER =
      new VitaExtractionService.PersonFacts("Father", LocalDate.of(1959, 8, 20), "male");
  static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

  private ObjectNode draft(VitaExtractionService.PersonFacts who) {
    return VitaExtractionService.toDraft(PAGE, who, ExtractionRulesTest.RX, TODAY);
  }

  private JsonNode row(ObjectNode d, String name) {
    for (JsonNode t : d.path("tests")) if (t.path("name").asText().equals(name)) return t;
    throw new AssertionError("no row " + name);
  }

  @Test
  void sixRowsAndTheCollectionDate() {
    ObjectNode d = draft(MOTHER);
    assertThat(d.path("kind").asText()).isEqualTo("lab_report");
    assertThat(d.path("tests")).hasSize(6);
    assertThat(d.path("rowCount").asInt()).isEqualTo(6);
    assertThat(d.path("documentDate").asText()).isEqualTo("2026-09-15");
    assertThat(d.path("documentDateAsPrinted").asText()).isEqualTo("15/09/2026");
  }

  @Test
  void thePatientCheckPassesForMotherAndWarnsForFather() {
    assertThat(draft(MOTHER).path("patientCheck").path("status").asText()).isEqualTo("match");
    JsonNode father = draft(FATHER).path("patientCheck");
    assertThat(father.path("status").asText()).isEqualTo("mismatch");
    assertThat(father.path("reasons").toString()).contains("female").contains("age 62");
  }

  @Test
  void theCommaCases() {
    ObjectNode d = draft(MOTHER);
    JsonNode creatinine = row(d, "Serum Creatinine");
    assertThat(creatinine.path("value").decimalValue()).isEqualByComparingTo("0.9");
    assertThat(creatinine.path("valueAsPrinted").asText()).isEqualTo("0,9");
    assertThat(creatinine.path("confidence").asDouble()).isLessThan(VitaExtractionService.CHECK_BELOW);
    assertThat(creatinine.path("needsCheck").asBoolean()).isTrue();

    JsonNode tlc = row(d, "Total Leucocyte Count");
    assertThat(tlc.path("value").decimalValue()).isEqualByComparingTo("7500");
    assertThat(tlc.path("refLow").decimalValue()).isEqualByComparingTo("4000");
    assertThat(tlc.path("refHigh").decimalValue()).isEqualByComparingTo("11000");
    assertThat(tlc.path("needsCheck").asBoolean()).isFalse();

    JsonNode platelets = row(d, "Platelet Count");
    assertThat(platelets.path("value").decimalValue()).isEqualByComparingTo("250000");
    assertThat(platelets.path("refLow").decimalValue()).isEqualByComparingTo("150000");
    assertThat(platelets.path("refHigh").decimalValue()).isEqualByComparingTo("410000");
    assertThat(platelets.path("needsCheck").asBoolean()).isFalse();
  }

  @Test
  void threeRowsAreHighByTheLabsRangesAndTheRestNormal() {
    ObjectNode d = draft(MOTHER);
    List<String> high = new ArrayList<>();
    for (JsonNode t : d.path("tests")) if (t.path("flag").asText().equals("high")) high.add(t.path("name").asText());
    assertThat(high).containsExactly("HbA1c", "Glucose Fasting (FBS)", "LDL Cholesterol");
    assertThat(row(d, "Serum Creatinine").path("flag").asText()).isEqualTo("normal");
    assertThat(row(d, "Total Leucocyte Count").path("flag").asText()).isEqualTo("normal");
    assertThat(row(d, "Platelet Count").path("flag").asText()).isEqualTo("normal");
    // Printed unit and range are kept as printed; conversion is the normaliser's (7b).
    assertThat(row(d, "LDL Cholesterol").path("unit").asText()).isEqualTo("mmol/L");
    assertThat(row(d, "LDL Cholesterol").path("refHigh").decimalValue()).isEqualByComparingTo("2.6");
    assertThat(row(d, "LDL Cholesterol").path("refHighInclusive").asBoolean()).isFalse();
  }
}
