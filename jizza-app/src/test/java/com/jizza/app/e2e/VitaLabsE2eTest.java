package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.platform.usecase.vita.documents.VitaExtractionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Lab results over HTTP (spec 5.4, 5.5, 5.6, Fixtures A and D): a confirmed lab-report document
 * becomes a report; the trend of HbA1c across three reports with the lab flag and the target status
 * kept apart; the change after a doctor's target; corrections; checks on the condition page.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaLabsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;
  private ApiClient api;
  private String mother;
  private String condition;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();
    String email = "vita-labs-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    mother = api.post("/api/people", Map.of("displayName", "Mother", "kind", "parent", "type", "individual", "dob", "1964-02-10"))
        .json().path("id").asText();
    assertThat(api.put("/api/vita/people/" + mother + "/profile", Map.of("sexAtBirth", "female")).ok()).isTrue();
    var c = api.post("/api/vita/people/" + mother + "/conditions",
        Map.of("name", "Type 2 diabetes", "template", "diabetes_t2", "status", "active"));
    assertThat(c.ok()).as(c.body()).isTrue();
    condition = c.json().path("id").asText();
  }

  /** Uploads a fictional page, reads it with the scripted reply, and returns the document id. */
  private String read(List<String> lines, String reply) throws Exception {
    var up = api.upload("/api/vita/people/" + mother + "/documents", Map.of(), "report.pdf", "application/pdf",
        VitaDocumentsE2eTest.pdf(List.of(lines), null));
    assertThat(up.status()).as(up.body()).isEqualTo(201);
    String id = up.json().path("id").asText();
    model.scriptFor(VitaExtractionService.READER_NAME, LlmResponse.text(reply));
    var read = api.post("/api/vita/documents/" + id + "/extract", Map.of());
    assertThat(read.ok()).as(read.body()).isTrue();
    return id;
  }

  private String confirm(String document) {
    var c = api.post("/api/vita/documents/" + document + "/confirm", Map.of());
    assertThat(c.ok()).as(c.body()).isTrue();
    String report = c.json().path("saved").path("labReport").asText();
    assertThat(report).isNotBlank();
    return report;
  }

  /** One of Fixture A's reports: HbA1c and fasting glucose on a fictional page. */
  private String fixtureA(String collected, String hba1c, String fasting) throws Exception {
    List<String> lines = List.of("FICTIONAL PATH LAB (fictional sample for software testing)",
        "Age/Sex: 62/F      Collected: " + collected, "HbA1c   " + hba1c + "   %   4.0-5.6",
        "Glucose Fasting (FBS)   " + fasting + "   mg/dL   70-100");
    String reply = """
        {"kind": "lab_report", "documentDate": "%s", "labName": "Fictional Path Lab",
         "patients": [{"age": "62", "sex": "F"}],
         "tests": [{"name": "HbA1c", "value": "%s", "unit": "%%", "range": "4.0-5.6"},
                   {"name": "Glucose Fasting (FBS)", "value": "%s", "unit": "mg/dL", "range": "70-100"}]}"""
        .formatted(collected, hba1c, fasting);
    return confirm(read(lines, reply));
  }

  private List<String> statuses(JsonNode trend) {
    List<String> out = new ArrayList<>();
    trend.path("points").forEach(p -> out.add(p.path("target").path("status").asText()));
    return out;
  }

  @Test
  void fixtureA_hba1cAcrossThreeReportsThenTheDoctorsTarget() throws Exception {
    // Nothing is a lab result until the review is confirmed.
    String pending = read(VitaDocumentsE2eTest.FIXTURE_D, VitaDocumentsE2eTest.FIXTURE_D_REPLY);
    assertThat(api.get("/api/vita/people/" + mother + "/labs").json()).isEmpty();
    assertThat(api.delete("/api/vita/documents/" + pending + "/draft").ok()).isTrue();

    fixtureA("10/03/2026", "8.2", "142");
    fixtureA("12/06/2026", "7.6", "128");
    fixtureA("15/09/2026", "7.1", "118");
    assertThat(api.get("/api/vita/people/" + mother + "/labs").json()).hasSize(3);

    JsonNode hba1c = api.get("/api/vita/people/" + mother + "/labs/trend?test=hba1c").json();
    assertThat(hba1c.path("count").asInt()).isEqualTo(3);
    assertThat(hba1c.path("changeSinceFirst").path("by").decimalValue()).isEqualByComparingTo("-1.1");
    assertThat(hba1c.path("changeSinceFirst").path("direction").asText()).isEqualTo("down");
    assertThat(statuses(hba1c)).containsExactly("outside_range", "outside_range", "outside_range");
    assertThat(hba1c.path("target").path("range").asText()).isEqualTo("7.0 or lower");
    assertThat(hba1c.path("target").path("label").asText()).isEqualTo("Vita default, needs verification");
    JsonNode r3 = hba1c.path("points").get(2);
    assertThat(r3.path("date").asText()).isEqualTo("2026-09-15");
    // Two results, never merged: the lab says high (4.0–5.6), the target says outside 7.0 or lower.
    assertThat(r3.path("labFlag").path("flag").asText()).isEqualTo("high");
    assertThat(r3.path("labFlag").path("label").asText()).isEqualTo("Lab: high (4.0–5.6)");
    assertThat(r3.path("secondary").get(0).path("shown").asText()).isEqualTo("54.1");
    assertThat(r3.path("secondary").get(0).path("needsVerification").asBoolean()).isTrue();
    assertThat(r3.path("secondary").get(1).path("shown").asText()).isEqualTo("157");
    assertThat(r3.path("secondary").get(1).path("needsVerification").asBoolean()).isTrue();

    JsonNode fasting = api.get("/api/vita/people/" + mother + "/labs/trend?test=fasting_glucose").json();
    assertThat(statuses(fasting)).containsExactly("outside_range", "in_range", "in_range");
    assertThat(fasting.path("target").path("range").asText()).isEqualTo("80–130");

    // Dr Meera Rao sets "HbA1c 7.5 or lower" on 1 Oct 2026.
    String doctor = api.post("/api/vita/doctors", Map.of("name", "Meera Rao")).json().path("id").asText();
    var target = api.put("/api/vita/people/" + mother + "/ranges", Map.of("labTestCode", "hba1c", "high", 7.5,
        "setBy", "doctor", "doctorId", doctor, "setOn", "2026-10-01"));
    assertThat(target.ok()).as(target.body()).isTrue();
    hba1c = api.get("/api/vita/people/" + mother + "/labs/trend?test=hba1c").json();
    assertThat(statuses(hba1c)).containsExactly("outside_range", "outside_range", "in_range");
    assertThat(hba1c.path("target").path("label").asText()).isEqualTo("from Dr Meera Rao, set on 1 Oct 2026");
    // The lab flag does not move with the target.
    assertThat(hba1c.path("points").get(2).path("labFlag").path("flag").asText()).isEqualTo("high");
    var ranges = api.get("/api/vita/people/" + mother + "/ranges").json();
    boolean listed = false;
    for (JsonNode r : ranges) listed |= "hba1c".equals(r.path("labTestCode").asText()) && "doctor".equals(r.path("source").asText());
    assertThat(listed).as(ranges.toString()).isTrue();

    // The diabetes page now shows the lab values and the checks.
    var summary = api.get("/api/vita/people/" + mother + "/conditions/" + condition + "/summary").json();
    assertThat(summary.has("labs")).isFalse();
    assertThat(summary.path("labResults").get(0).path("test").path("code").asText()).isEqualTo("hba1c");
    JsonNode hba1cCheck = null;
    for (JsonNode c : summary.path("checks")) if ("hba1c".equals(c.path("check").asText())) hba1cCheck = c;
    assertThat(hba1cCheck).isNotNull();
    assertThat(hba1cCheck.path("lastDone").asText()).isEqualTo("2026-09-15");
    assertThat(hba1cCheck.path("lastDoneFrom").asText()).isEqualTo("lab");
    // In range of the doctor's target: the "at target" interval (at least twice a year).
    assertThat(hba1cCheck.path("intervalUsed").asText()).isEqualTo("atTarget");
    assertThat(hba1cCheck.path("dueOn").asText()).isEqualTo("2027-03-15");
    assertThat(hba1cCheck.path("needsVerification").asBoolean()).isTrue();
  }

  @Test
  void fixtureD_theReportShowsConvertedFiguresAndThreeLabFlagsHigh() throws Exception {
    String document = read(VitaDocumentsE2eTest.FIXTURE_D, VitaDocumentsE2eTest.FIXTURE_D_REPLY);
    // The browser's PDF viewer does not run in a sandbox: a PDF is served without one.
    assertThat(api.get("/api/vita/documents/" + document + "/file").status()).isEqualTo(200);
    assertThat(api.lastHeaders().firstValue("content-security-policy")).contains("default-src 'none'; frame-ancestors 'self'");
    assertThat(api.lastHeaders().allValues("x-frame-options")).containsExactly("SAMEORIGIN");
    String report = confirm(document);
    JsonNode view = api.get("/api/vita/labs/" + report).json();
    assertThat(view.path("date").asText()).isEqualTo("2026-09-15");
    JsonNode rows = view.path("results");
    assertThat(rows).hasSize(6);
    List<String> flags = new ArrayList<>();
    rows.forEach(r -> flags.add(r.path("labFlag").path("flag").asText()));
    assertThat(flags).containsExactly("high", "high", "normal", "high", "normal", "normal");
    assertThat(rows.get(1).path("test").path("code").asText()).isEqualTo("fasting_glucose");
    JsonNode ldl = rows.get(3);
    assertThat(ldl.path("unit").asText()).isEqualTo("mmol/L");
    assertThat(ldl.path("valueCanonical").decimalValue()).isEqualByComparingTo("119.877");
    assertThat(ldl.path("shown").path("value").asText()).isEqualTo("120");
    assertThat(ldl.path("shown").path("unit").asText()).isEqualTo("mg/dL");
    assertThat(ldl.path("shown").path("range").asText()).isEqualTo("<101");
    assertThat(ldl.path("shown").path("needsVerification").asBoolean()).isTrue();
    assertThat(rows.get(4).path("valueNum").decimalValue()).isEqualByComparingTo("7500");
    assertThat(rows.get(5).path("valueNum").decimalValue()).isEqualByComparingTo("250000");
    assertThat(rows.get(5).path("shown").path("range").asText()).isEqualTo("150000–410000");
    assertThat(rows.get(2).path("confidence").decimalValue()).isLessThan(new java.math.BigDecimal("0.8"));
    // HbA1c: the lab says high; the target status is a separate thing.
    assertThat(rows.get(0).path("target").path("status").asText()).isEqualTo("outside_range");
    assertThat(rows.get(0).path("target").path("range").asText()).isEqualTo("7.0 or lower");
    // The count has no target: "not set", never a guess.
    assertThat(rows.get(4).path("target").path("status").asText()).isEqualTo("not_set");
  }

  @Test
  void aReportIsCorrectedAndRemoved() throws Exception {
    String report = confirm(read(VitaDocumentsE2eTest.FIXTURE_D, VitaDocumentsE2eTest.FIXTURE_D_REPLY));
    JsonNode rows = api.get("/api/vita/labs/" + report).json().path("results");
    // Every other response still may not be framed at all.
    assertThat(api.lastHeaders().allValues("x-frame-options")).containsExactly("DENY");
    String creatinine = rows.get(2).path("id").asText();
    String platelets = rows.get(5).path("id").asText();
    var fixed = api.put("/api/vita/labs/" + report, Map.of("labName", "Corrected Lab", "results", List.of(
        Map.of("id", creatinine, "valueAsPrinted", "0.9"), Map.of("id", platelets, "remove", true))));
    assertThat(fixed.ok()).as(fixed.body()).isTrue();
    JsonNode after = fixed.json();
    assertThat(after.path("report").path("labName").asText()).isEqualTo("Corrected Lab");
    assertThat(after.path("results")).hasSize(5);
    assertThat(after.path("results").get(2).path("valueNum").decimalValue()).isEqualByComparingTo("0.9");
    assertThat(after.path("results").get(2).path("confidence").decimalValue()).isEqualByComparingTo("1");
    assertThat(after.path("results").get(2).path("labFlag").path("flag").asText()).isEqualTo("normal");
    // A row of another report cannot be changed through this one.
    var stray = api.put("/api/vita/labs/" + report, Map.of("results", List.of(Map.of("id", UUID.randomUUID().toString()))));
    assertThat(stray.status()).isEqualTo(400);
    assertThat(api.get("/api/vita/people/" + mother + "/labs/trend?test=nothing").status()).isEqualTo(400);

    assertThat(api.delete("/api/vita/labs/" + report).status()).isEqualTo(204);
    assertThat(api.get("/api/vita/labs/" + report).status()).isEqualTo(404);
    assertThat(api.get("/api/vita/people/" + mother + "/labs").json()).isEmpty();
  }

  @Test
  void checksAreDueOnlyFromADoctorsDateOrAConfiguredInterval() {
    var eye = api.post("/api/vita/people/" + mother + "/checks", Map.of("kind", "eye", "doneOn", "2026-05-01"));
    assertThat(eye.ok()).as(eye.body()).isTrue();
    var dental = api.post("/api/vita/people/" + mother + "/checks", Map.of("kind", "dental", "nextDueOn", "2027-01-10"));
    assertThat(dental.ok()).as(dental.body()).isTrue();
    assertThat(api.post("/api/vita/people/" + mother + "/checks", Map.of("kind", "foot")).status()).isEqualTo(400);
    assertThat(api.get("/api/vita/people/" + mother + "/checks").json()).hasSize(2);

    var summary = api.get("/api/vita/people/" + mother + "/conditions/" + condition + "/summary").json();
    Map<String, JsonNode> checks = new java.util.HashMap<>();
    summary.path("checks").forEach(c -> checks.put(c.path("check").asText(), c));
    // Eye: at least once a year (config, needs verification), from the date it was done.
    assertThat(checks.get("eye").path("dueOn").asText()).isEqualTo("2027-05-01");
    assertThat(checks.get("eye").path("dueFrom").asText()).isEqualTo("interval");
    // Foot: an interval is set but nothing is recorded yet.
    assertThat(checks.get("foot").path("status").asText()).isEqualTo("no_record");
    // Kidney function and lipids: no interval in config, so "not set".
    assertThat(checks.get("kidney_function").path("status").asText()).isEqualTo("not_set");
    assertThat(checks.get("lipids").path("label").asText()).isEqualTo("Not set: ask your doctor");
    // HbA1c: no result and no target judgement yet, so no interval is chosen.
    assertThat(checks.get("hba1c").path("status").asText()).isEqualTo("not_set");

    String id = eye.json().path("id").asText();
    var moved = api.put("/api/vita/people/" + mother + "/checks/" + id, Map.of("nextDueOn", "2026-12-20"));
    assertThat(moved.ok()).as(moved.body()).isTrue();
    summary = api.get("/api/vita/people/" + mother + "/conditions/" + condition + "/summary").json();
    summary.path("checks").forEach(c -> checks.put(c.path("check").asText(), c));
    assertThat(checks.get("eye").path("dueOn").asText()).isEqualTo("2026-12-20");
    assertThat(checks.get("eye").path("dueFrom").asText()).isEqualTo("doctor");
    assertThat(api.delete("/api/vita/people/" + mother + "/checks/" + id).ok()).isTrue();
  }
}
