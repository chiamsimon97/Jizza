package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Vita Health, Phase 1a part 1 through the API: readings of every typed kind with their
 * evaluation, a doctor's target beating the Vita default, the red flag that asks "Is 182/112
 * right?" before alerting the manager, trends, condition pages, doctors with next visits and the
 * targets they set, visits, ranges and Home.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaRecordAndVitalsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  private ApiClient api;
  private String father;
  private String mother;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "vita1a-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    father = person("Father");
    mother = person("Mother");
  }

  private String person(String name) {
    var p = api.post("/api/people", Map.of("displayName", name, "kind", "parent", "type", "individual"));
    assertThat(p.ok()).as(p.body()).isTrue();
    return p.json().path("id").asText();
  }

  private String path(String person, String rest) {
    return "/api/vita/people/" + person + rest;
  }

  /**
   * Times relative to now in India: Home shows a red flag only for a while, and trends look back a
   * number of days, so fixed dates would stop passing as the calendar moves on.
   */
  private static String minutesAgo(int minutes) {
    return java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).minusMinutes(minutes)
        .truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString();
  }

  private static String daysAgo(int days, String time) {
    return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(days) + "T" + time;
  }

  private JsonNode bp(String person, int sys, int dia, String takenAt) {
    var r = api.post(path(person, "/measurements"), Map.of("kind", "bp", "value", sys, "value2", dia,
        "value3", 72, "takenAt", takenAt));
    assertThat(r.ok()).as(r.body()).isTrue();
    return r.json();
  }

  @Test
  void aHomeReadingUsesTheVitaDefaultUntilTheDoctorSetsATarget() {
    JsonNode r = bp(father, 138, 84, "2026-11-20T07:30");
    JsonNode e = r.path("evaluation");
    assertThat(e.path("status").asText()).isEqualTo("outside_range");
    assertThat(e.path("label").asText()).isEqualTo("Vita default, needs verification");
    assertThat(e.path("range").asText()).isEqualTo("below 135/85");
    assertThat(r.path("setting").asText()).isEqualTo("home");

    var doctor = api.post("/api/vita/doctors", Map.of("name", "S Iyer", "speciality", "Cardiology",
        "phone", "+91 80 2222 3333"));
    assertThat(doctor.ok()).as(doctor.body()).isTrue();
    String did = doctor.json().path("id").asText();
    var target = api.put(path(father, "/ranges"), Map.of("kind", "bp", "setting", "home", "high", 140,
        "high2", 90, "highInclusive", false, "setBy", "doctor", "doctorId", did, "setOn", "2026-11-01"));
    assertThat(target.ok()).as(target.body()).isTrue();

    JsonNode now = api.get(path(father, "/measurements/" + r.path("id").asText())).json().path("evaluation");
    assertThat(now.path("status").asText()).isEqualTo("in_range");
    assertThat(now.path("label").asText()).isEqualTo("from Dr S Iyer, set on 1 Nov 2026");
    assertThat(now.path("needsVerification").asBoolean()).isFalse();

    // The ranges screen names who set each, and what is not set.
    JsonNode ranges = api.get(path(father, "/ranges")).json();
    assertThat(find(ranges, "bp", null, "home").path("label").asText()).startsWith("from Dr S Iyer");
    assertThat(find(ranges, "bp", null, "clinic").path("label").asText()).isEqualTo("Vita default, needs verification");
    assertThat(find(ranges, "spo2", null, null).path("label").asText()).isEqualTo("Not set: ask your doctor");
    assertThat(find(ranges, "glucose", "before_meal", null).path("status").asText()).isEqualTo("not_set");

    // The doctors list shows the target the doctor set, and the next visit.
    assertThat(api.post(path(father, "/visits"), Map.of("doctorId", did, "scheduledAt", "2030-01-05T10:30",
        "reason", "BP review")).ok()).isTrue();
    JsonNode doctors = api.get("/api/vita/doctors").json();
    assertThat(doctors).hasSize(1);
    assertThat(doctors.get(0).path("nextVisit").path("personName").asText()).isEqualTo("Father");
    assertThat(doctors.get(0).path("targets")).hasSize(1);
    assertThat(api.get(path(father, "/home")).json().path("nextVisit").path("reason").asText()).isEqualTo("BP review");
  }

  private static JsonNode find(JsonNode ranges, String kind, String context, String setting) {
    for (JsonNode r : ranges) {
      if (kind.equals(r.path("kind").asText()) && (context == null ? r.path("context").isNull() : context.equals(r.path("context").asText()))
          && (setting == null ? r.path("setting").isNull() : setting.equals(r.path("setting").asText()))) return r;
    }
    throw new AssertionError("no range for " + kind);
  }

  @Test
  void aTypedRedFlagAsksFirstThenAlertsTheManagerOnce() {
    int before = api.get("/api/notifications").json().size();
    JsonNode r = bp(father, 182, 112, minutesAgo(30));
    assertThat(r.path("evaluation").path("status").asText()).isEqualTo("red_flag");
    assertThat(r.path("evaluation").path("redFlag").path("level").asText()).isEqualTo("urgent");
    assertThat(r.path("confirmNeeded").asBoolean()).isTrue();
    assertThat(r.path("confirmQuestion").asText()).isEqualTo("Is 182/112 right?");
    assertThat(api.get("/api/notifications").json().size()).as("nobody alerted before the yes").isEqualTo(before);

    String id = r.path("id").asText();
    var no = api.post(path(father, "/measurements/" + id + "/red-flag"), Map.of("right", false));
    assertThat(no.json().path("alerted").asBoolean()).isFalse();
    assertThat(api.get("/api/notifications").json().size()).isEqualTo(before);

    var yes = api.post(path(father, "/measurements/" + id + "/red-flag"), Map.of("right", true, "signsNow", false));
    assertThat(yes.ok()).as(yes.body()).isTrue();
    assertThat(yes.json().path("alerted").asBoolean()).isTrue();
    assertThat(yes.json().path("help").path("level").asText()).isEqualTo("urgent");
    JsonNode notes = api.get("/api/notifications").json();
    assertThat(notes.size()).isEqualTo(before + 1);
    String body = notes.toString();
    assertThat(body).contains("Father: a blood pressure reading needs attention today.");
    assertThat(body).doesNotContain("182");

    // The same answer again alerts nobody twice; a yes to the signs raises it to "Call now".
    assertThat(api.post(path(father, "/measurements/" + id + "/red-flag"), Map.of("right", true)).json()
        .path("alerted").asBoolean()).isFalse();
    var emergency = api.post(path(father, "/measurements/" + id + "/red-flag"), Map.of("right", true, "signsNow", true));
    assertThat(emergency.json().path("evaluation").path("redFlag").path("level").asText()).isEqualTo("emergency");
    assertThat(emergency.json().path("alerted").asBoolean()).isTrue();

    JsonNode banners = api.get(path(father, "/home")).json().path("redFlags");
    assertThat(banners).hasSize(1);
    assertThat(banners.get(0).path("level").asText()).isEqualTo("emergency");
    assertThat(banners.get(0).path("wording").asText()).startsWith("Call now");

    // 180/120 is not a red flag: the rule is "above".
    assertThat(bp(mother, 180, 120, minutesAgo(20)).path("evaluation").path("status").asText())
        .isNotEqualTo("red_flag");
  }

  @Test
  void sugarUsesTheDiabetesSetOnlyWithAnActiveConditionAndALowReadingIsAFlag() {
    var before = api.post(path(mother, "/measurements"), Map.of("kind", "glucose", "value", 142, "context", "fasting",
        "takenAt", daysAgo(3, "07:00")));
    assertThat(before.json().path("evaluation").path("status").asText()).isEqualTo("not_set");
    var c = api.post(path(mother, "/conditions"), Map.of("name", "Type 2 diabetes", "template", "diabetes_t2"));
    String cid = c.json().path("id").asText();
    JsonNode fasting = api.get(path(mother, "/measurements/" + before.json().path("id").asText())).json().path("evaluation");
    assertThat(fasting.path("status").asText()).isEqualTo("outside_range");
    assertThat(fasting.path("range").asText()).isEqualTo("80–130");
    var after = api.post(path(mother, "/measurements"), Map.of("kind", "glucose", "value", 170, "context", "after_meal",
        "meal", "lunch", "takenAt", daysAgo(3, "14:00")));
    assertThat(after.json().path("evaluation").path("status").asText()).isEqualTo("in_range");
    var low = api.post(path(mother, "/measurements"), Map.of("kind", "glucose", "value", 62, "context", "random",
        "takenAt", daysAgo(2, "22:00")));
    assertThat(low.json().path("evaluation").path("redFlag").path("ruleId").asText()).isEqualTo("low_sugar");
    assertThat(api.post(path(mother, "/measurements"), Map.of("kind", "glucose", "value", 7.8, "unit", "mmol/L")).status())
        .isEqualTo(400);

    JsonNode summary = api.get(path(mother, "/conditions/" + cid + "/summary")).json();
    assertThat(summary.path("template").asText()).isEqualTo("diabetes_t2");
    assertThat(summary.path("labs").asText()).isEqualTo("Add a report to see HbA1c.");
    assertThat(summary.path("lowReadings")).hasSize(1);
    List<String> states = new ArrayList<>();
    summary.path("checks").forEach(x -> states.add(x.path("check").asText() + ":" + x.path("state").asText()));
    assertThat(states).contains("hba1c:needs_verification", "eye:needs_verification", "kidney_function:not_set", "lipids:not_set");

    JsonNode trend = api.get(path(mother, "/measurements/trend?kind=glucose&days=30&context=before_meal")).json();
    assertThat(trend.path("range").path("label").asText()).isEqualTo("Vita default, needs verification");
  }

  @Test
  void otherKindsTheirFormatsAndASecondReading() {
    var hr = api.post(path(father, "/measurements"), Map.of("kind", "heart_rate", "value", 72));
    assertThat(hr.json().path("evaluation").path("status").asText()).isEqualTo("not_set");
    assertThat(api.post(path(father, "/measurements"), Map.of("kind", "spo2", "value", 101)).status()).isEqualTo(400);
    var f = api.post(path(father, "/measurements"), Map.of("kind", "temperature", "value", 98.6, "unit", "F"));
    assertThat(f.json().path("value").decimalValue()).isEqualByComparingTo("37");
    assertThat(api.post(path(father, "/measurements"), Map.of("kind", "steps", "value", 4000)).status()).isEqualTo(400);

    JsonNode first = bp(father, 150, 95, daysAgo(2, "07:30"));
    var second = api.post(path(father, "/measurements?secondOf=" + first.path("id").asText()),
        Map.of("kind", "bp", "value", 140, "value2", 85, "takenAt", daysAgo(2, "07:32")));
    assertThat(second.ok()).as(second.body()).isTrue();
    assertThat(second.json().path("pairId").asText()).isEqualTo(first.path("id").asText());
    JsonNode trend = api.get(path(father, "/measurements/trend?kind=bp&days=30")).json().path("trend");
    assertThat(trend.path("count").asInt()).isEqualTo(1);
    assertThat(trend.path("all").path("value").path("mean").decimalValue()).isEqualByComparingTo("145");
  }

  @Test
  void doctorsAndVisitsAreHouseholdAndPersonRows() {
    var d = api.post("/api/vita/doctors", Map.of("kind", "lab", "name", "City Lab"));
    String did = d.json().path("id").asText();
    assertThat(api.put("/api/vita/doctors/" + did, Map.of("phone", "+91 80 1111 2222")).json().path("phone").asText())
        .isEqualTo("+91 80 1111 2222");
    var v = api.post(path(mother, "/visits"), Map.of("doctorId", did, "scheduledAt", "2026-11-01T09:00",
        "status", "done", "followUpOn", "2027-02-01"));
    assertThat(v.ok()).as(v.body()).isTrue();
    assertThat(api.post(path(mother, "/visits"), Map.of("doctorId", UUID.randomUUID().toString(),
        "scheduledAt", "2026-11-01T09:00")).status()).isEqualTo(404);
    assertThat(api.get(path(mother, "/visits")).json()).hasSize(1);
    assertThat(api.delete("/api/vita/doctors/" + did).ok()).isTrue();
    assertThat(api.get("/api/vita/doctors").json()).isEmpty();
  }
}
