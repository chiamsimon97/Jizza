package com.jizza.platform.usecase.vita.labs;

import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaStore.LabTestRow;
import java.util.List;

/** The part of V70's seed these tests use, copied as written there (names, units, conversions). */
final class LabSeed {

  private LabSeed() {}

  static final String CHOLESTEROL = """
      [{"from": "mmol/L", "multiply": 38.67, "source": "Cholesterol mmol/L to mg/dL factor (spec 5.4)", "verifiedOn": null}]""";
  static final String NGSP = """
      [{"from": "mmol/mol", "multiply": 0.09148, "add": 2.152, "decimals": 1, "source": "NGSP master equation (spec 5.4)", "verifiedOn": null}]""";

  static List<LabTestRow> rows() {
    return List.of(
        row("hba1c", "HbA1c", List.of("HbA1c", "Glycosylated Haemoglobin", "Glycated Haemoglobin", "Glycated Hemoglobin"), "%", List.of(), 1, NGSP, "diabetes"),
        row("fasting_glucose", "Fasting glucose", List.of("FBS", "Glucose Fasting", "Fasting Glucose", "Glucose, Fasting"), "mg/dL", List.of("mg%"), 0, "[]", "diabetes"),
        row("creatinine", "Serum creatinine", List.of("Serum Creatinine", "Creatinine"), "mg/dL", List.of("mg%"), 2, "[]", "kidney"),
        row("egfr", "eGFR", List.of("eGFR", "GFR Estimated"), "mL/min/1.73m2", List.of(), 0, "[]", "kidney"),
        row("urea", "Blood urea", List.of("Blood Urea", "Urea"), "mg/dL", List.of(), 0, "[]", "kidney"),
        row("uric_acid", "Uric acid", List.of("Uric Acid"), "mg/dL", List.of(), 1, "[]", "kidney"),
        row("total_cholesterol", "Total cholesterol", List.of("Total Cholesterol", "Cholesterol, Total", "Cholesterol-Total"), "mg/dL", List.of("mg%"), 0, CHOLESTEROL, "lipids"),
        row("ldl", "LDL cholesterol", List.of("LDL", "LDL Cholesterol", "LDL Cholesterol, Calculated"), "mg/dL", List.of("mg%"), 0, CHOLESTEROL, "lipids"),
        row("hdl", "HDL cholesterol", List.of("HDL", "HDL Cholesterol"), "mg/dL", List.of("mg%"), 0, CHOLESTEROL, "lipids"),
        row("triglycerides", "Triglycerides", List.of("Triglycerides"), "mg/dL", List.of("mg%"), 0,
            "[{\"from\": \"mmol/L\", \"multiply\": 88.57, \"source\": \"spec 5.4\"}]", "lipids"),
        row("vldl", "VLDL cholesterol", List.of("VLDL", "VLDL Cholesterol"), "mg/dL", List.of(), 0, "[]", "lipids"),
        row("non_hdl", "Non-HDL cholesterol", List.of("Non-HDL Cholesterol"), "mg/dL", List.of(), 0, "[]", "lipids"),
        row("tsh", "TSH", List.of("TSH", "TSH (Ultrasensitive)"), "µIU/mL", List.of("uIU/mL", "mIU/L"), 2, "[]", "thyroid"),
        row("haemoglobin", "Haemoglobin", List.of("Haemoglobin", "Hemoglobin", "Hb", "Haemoglobin (Hb)"), "g/dL", List.of("gm%"), 1, "[]", "blood_count"),
        row("tlc", "Total leucocyte count", List.of("Total Leucocyte Count", "TLC", "WBC"), "/cumm", List.of("cells/cumm"), 0, "[]", "blood_count"),
        row("platelets", "Platelet count", List.of("Platelet Count", "Platelets"), "/cumm", List.of(), 0, "[]", "blood_count"));
  }

  static LabNormaliser normaliser() {
    return new LabNormaliser(rows());
  }

  private static LabTestRow row(String code, String name, List<String> synonyms, String unit, List<String> aliases,
                                int decimals, String conversions, String group) {
    return new LabTestRow(code, name, synonyms, unit, aliases, decimals, Json.read(conversions), group, 0);
  }
}
