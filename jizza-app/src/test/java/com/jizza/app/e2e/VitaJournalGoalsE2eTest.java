package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The journal and goals (Vita spec 5.7, Phase 2c): one journal entry a day; a goal for blood
 * pressure, sugar or HbA1c reads the doctor's target or the configured Vita default and never a
 * figure of its own or the person's own range; without either it cannot be created; Vita offers no
 * plan figure; check-ins fill from measurements and a goal is met after the configured weeks.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaJournalGoalsE2eTest extends E2eSupport {

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  @LocalServerPort private int port;

  private ApiClient signUp() {
    ApiClient c = new ApiClient(port);
    c.prime();
    String email = "vita-goals-" + UUID.randomUUID() + "@jizza.local";
    assertThat(c.post("/api/auth/register", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();
    assertThat(c.post("/api/auth/login", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();
    return c;
  }

  private static JsonNode ok(ApiClient.Result r) {
    assertThat(r.ok()).as(r.body()).isTrue();
    return r.json();
  }

  private static String me(ApiClient api) {
    return ok(api.get("/api/people")).get(0).path("id").asText();
  }

  private static LocalDate today() {
    return LocalDate.now(INDIA);
  }

  @Test
  void oneJournalEntryADayThatOnlyTheRecordsManagerReads() {
    ApiClient api = signUp();
    String me = me(api);
    String day = "/api/vita/people/" + me + "/journal/" + today();
    JsonNode saved = ok(api.put(day, Map.of("mood", 4, "energy", 3, "pain", 2,
        "symptoms", List.of("headache", "headache", "tired"), "notes", "Slept late")));
    assertThat(saved.path("symptoms")).hasSize(2);
    // The same day again changes the entry rather than adding one.
    ok(api.put(day, Map.of("pain", 5)));
    JsonNode entry = ok(api.get(day));
    assertThat(entry.path("pain").asInt()).isEqualTo(5);
    assertThat(entry.path("mood").asInt()).isEqualTo(4);
    ok(api.put("/api/vita/people/" + me + "/journal/" + today().minusDays(3), Map.of("mood", 2)));
    assertThat(ok(api.get("/api/vita/people/" + me + "/journal"))).hasSize(2);
    assertThat(ok(api.get("/api/vita/people/" + me + "/journal?from=" + today().minusDays(1) + "&to=" + today()))).hasSize(1);

    assertThat(api.put("/api/vita/people/" + me + "/journal/" + today().plusDays(1), Map.of("mood", 3)).status()).isEqualTo(400);
    assertThat(api.put(day, Map.of("mood", 0)).status()).isEqualTo(400);
    assertThat(api.put(day, Map.of("mood", 6)).status()).isEqualTo(400);
    assertThat(api.put(day, Map.of("pain", 11)).status()).isEqualTo(400);
    assertThat(api.put(day, Map.of("cycleDay", 3)).status()).as("no cycle tracking in this build").isEqualTo(400);
    assertThat(api.put("/api/vita/people/" + me + "/journal/2026-13-01", Map.of("mood", 3)).status()).isEqualTo(400);
    assertThat(api.put("/api/vita/people/" + me + "/journal/" + today().minusDays(10), Map.of()).status()).isEqualTo(400);

    ApiClient other = signUp();
    assertThat(other.get(day).status()).isEqualTo(404);
    assertThat(other.get("/api/vita/people/" + me + "/journal").status()).isEqualTo(404);
    assertThat(other.put(day, Map.of("mood", 1)).status()).isEqualTo(404);
    assertThat(other.get("/api/vita/people/" + me + "/goals").status()).isEqualTo(404);

    assertThat(api.delete(day).status()).isEqualTo(204);
    assertThat(api.get(day).status()).isEqualTo(404);
  }

  @Test
  void aMedicalGoalReadsTheDoctorsTargetOrTheConfiguredDefaultNeverItsOwnFigure() {
    ApiClient api = signUp();
    String me = me(api);
    String goals = "/api/vita/people/" + me + "/goals";

    // HbA1c and sugar have no default outside the diabetes template, so neither can be created.
    JsonNode hba1c = ok(api.post("/api/vita/goals/preview", Map.of("personId", me, "kind", "hba1c")));
    assertThat(hba1c.path("canCreate").asBoolean()).isFalse();
    assertThat(hba1c.path("target").isNull()).isTrue();
    assertThat(hba1c.path("plan")).isEmpty();
    assertThat(api.post(goals, Map.of("kind", "hba1c")).status()).isEqualTo(400);
    assertThat(api.post(goals, Map.of("kind", "glucose_range", "context", "before_meal")).status()).isEqualTo(400);
    // A figure of the goal's own is refused for a medical goal.
    assertThat(api.post(goals, Map.of("kind", "bp_range", "target", 120, "target2", 80)).status()).isEqualTo(400);

    // Blood pressure: the configured default, marked for checking with the doctor. The person's own
    // range never becomes the goal's target.
    String ownRange = ok(api.put("/api/vita/people/" + me + "/ranges", Map.of("kind", "bp", "setting", "home",
        "high", 125, "high2", 85, "setBy", "user"))).path("id").asText();
    JsonNode bp = ok(api.post("/api/vita/goals/preview", Map.of("kind", "bp_range")));
    assertThat(bp.path("canCreate").asBoolean()).isTrue();
    assertThat(bp.path("target").path("source").asText()).isEqualTo("vita_default");
    assertThat(bp.path("target").path("needsVerification").asBoolean()).isTrue();
    assertThat(bp.path("target").path("configKey").asText()).isEqualTo("clinical.ranges.general.bp_home");
    assertThat(bp.path("message").asText()).contains("Confirm with your doctor");
    assertThat(bp.path("target").path("text").asText()).doesNotContain("125");
    JsonNode goal = ok(api.post(goals, Map.of("kind", "bp_range", "weeklyPlan", List.of("Walk 20 minutes after dinner"))));
    assertThat(goal.path("target").path("source").asText()).isEqualTo("vita_default");
    assertThat(goal.path("target").isNull()).isFalse();
    assertThat(goal.path("weeklyPlan")).hasSize(1);
    String goalId = goal.path("id").asText();

    // A doctor's target replaces the default on the goal, with no change to the goal itself.
    // (Only one live range per measure and setting is kept, so the person's own one goes first.)
    assertThat(api.delete("/api/vita/people/" + me + "/ranges/" + ownRange).status()).isBetween(200, 204);
    String doctor = ok(api.post("/api/vita/doctors", Map.of("name", "Meera Rao"))).path("id").asText();
    ok(api.put("/api/vita/people/" + me + "/ranges", Map.of("kind", "bp", "setting", "home", "high", 130,
        "high2", 80, "highInclusive", false, "setBy", "doctor", "doctorId", doctor, "setOn", "2026-10-01")));
    goal = ok(api.get(goals + "/" + goalId));
    assertThat(goal.path("target").path("source").asText()).isEqualTo("doctor");
    assertThat(goal.path("target").path("label").asText()).isEqualTo("from Dr Meera Rao, set on 1 Oct 2026");
    assertThat(goal.path("target").path("text").asText()).isEqualTo("below 130/80");
    assertThat(goal.path("target").path("needsVerification").asBoolean()).isFalse();
    assertThat(api.put(goals + "/" + goalId, Map.of("target", 110)).status()).isEqualTo(400);

    // The doctor's HbA1c target makes an HbA1c goal possible.
    ok(api.put("/api/vita/people/" + me + "/ranges", Map.of("labTestCode", "hba1c", "high", 7.5,
        "setBy", "doctor", "doctorId", doctor, "setOn", "2026-10-01")));
    JsonNode a1c = ok(api.post(goals, Map.of("kind", "hba1c")));
    assertThat(a1c.path("target").path("source").asText()).isEqualTo("doctor");
    assertThat(a1c.path("target").path("text").asText()).contains("7.5");

    // Every medical goal shown carries a target from a doctor or from config, never anything else.
    for (JsonNode g : ok(api.get(goals))) {
      if (!g.path("medical").asBoolean()) continue;
      assertThat(g.path("target").path("source").asText()).isIn("doctor", "vita_default");
      if ("vita_default".equals(g.path("target").path("source").asText())) {
        assertThat(g.path("target").path("configKey").asText()).startsWith("clinical.");
      }
    }
  }

  @Test
  void checkInsFillFromMeasurementsAndAGoalIsMetAfterTheConfiguredWeeks() {
    ApiClient api = signUp();
    String me = me(api);
    String goals = "/api/vita/people/" + me + "/goals";
    assertThat(api.post(goals, Map.of("kind", "weight")).status()).as("a weight goal needs its number").isEqualTo(400);
    assertThat(api.post(goals, Map.of("kind", "custom")).status()).as("a custom goal needs a name").isEqualTo(400);
    assertThat(api.post(goals, Map.of("kind", "steps", "target", 6000, "context", "before_meal")).status()).isEqualTo(400);
    // The latest weight on record says the goal is to come down.
    ok(api.post("/api/vita/people/" + me + "/measurements", Map.of("kind", "weight", "value", 72.8,
        "takenAt", today().minusWeeks(3) + "T08:00")));
    JsonNode weight = ok(api.post(goals, Map.of("kind", "weight", "target", 70)));
    assertThat(weight.path("startValue").decimalValue()).isEqualByComparingTo("72.8");
    assertThat(weight.path("unit").asText()).isEqualTo("kg");
    assertThat(weight.path("target").path("source").asText()).isEqualTo("you");
    String id = weight.path("id").asText();

    // This week's weights fill the week without a typed check-in.
    Map<String, Object> reading = new HashMap<>(Map.of("kind", "weight", "value", 72.4));
    ok(api.post("/api/vita/people/" + me + "/measurements", reading));
    reading.put("value", 72.0);
    ok(api.post("/api/vita/people/" + me + "/measurements", reading));
    JsonNode shown = ok(api.get(goals + "/" + id));
    JsonNode thisWeek = shown.path("checkins").get(shown.path("checkins").size() - 1);
    assertThat(thisWeek.path("source").asText()).isEqualTo("measurements");
    assertThat(thisWeek.path("value").decimalValue()).isEqualByComparingTo("72.2");
    assertThat(thisWeek.path("meets").asBoolean()).isFalse();

    // A typed value replaces it; with the weeks set to one, meeting the number marks the goal met.
    ok(api.put("/api/vita/config/goals.met_after_weeks", Map.of("value", Map.of("weeks", 1))));
    LocalDate monday = today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    assertThat(api.post(goals + "/" + id + "/checkins", Map.of("weekStart", monday.plusDays(1).toString(), "value", 69))
        .status()).as("a week starts on a Monday").isEqualTo(400);
    JsonNode met = ok(api.post(goals + "/" + id + "/checkins", Map.of("value", 69.5, "note", "Felt good")));
    assertThat(met.path("status").asText()).isEqualTo("met");
    assertThat(met.path("metOn").asText()).isEqualTo(today().toString());
    assertThat(met.path("weeksMetInARow").asInt()).isEqualTo(1);
    JsonNode last = met.path("checkins").get(met.path("checkins").size() - 1);
    assertThat(last.path("source").asText()).isEqualTo("manual");
    assertThat(last.path("value").decimalValue()).isEqualByComparingTo("69.5");

    // A blood pressure check-in needs both numbers.
    String bp = ok(api.post(goals, Map.of("kind", "bp_range"))).path("id").asText();
    assertThat(api.post(goals + "/" + bp + "/checkins", Map.of("value", 128)).status()).isEqualTo(400);
    JsonNode bpWeek = ok(api.post(goals + "/" + bp + "/checkins", Map.of("value", 128, "value2", 82)));
    assertThat(bpWeek.path("checkins").get(bpWeek.path("checkins").size() - 1).path("value2").decimalValue())
        .isEqualByComparingTo("82");

    assertThat(api.put(goals + "/" + id, Map.of("kind", "steps")).status()).isEqualTo(400);
    assertThat(ok(api.put(goals + "/" + id, Map.of("status", "paused"))).path("metOn").isNull()).isTrue();
    assertThat(api.delete(goals + "/" + id).status()).isEqualTo(204);
    assertThat(api.get(goals + "/" + id).status()).isEqualTo(404);
  }
}
