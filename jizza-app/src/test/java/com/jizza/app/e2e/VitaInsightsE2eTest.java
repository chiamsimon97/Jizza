package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaDoseStore;
import com.jizza.platform.usecase.vita.insights.VitaWeeklyNoteService;
import com.jizza.platform.usecase.vita.medicine.VitaMedicineUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaReminderService;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Insights (Vita spec 2.6, 5.9, Phase 4): the weekly note is assembled from rows and every
 * sentence names its rows; it goes once a week to the manager, never for a closed record; only
 * the manager (or the person themself) reads it. "Goes with" stays silent below the configured
 * journal days, and above them reports a pair with the days behind it, never a cause.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaInsightsE2eTest extends E2eSupport {

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private VitaWeeklyNoteService notes;
  @Autowired private VitaMedicineUseCase medicines;
  @Autowired private VitaReminderService reminders;
  @Autowired private VitaScopeResolver scopes;
  @Autowired private VitaDoseStore store;

  private ApiClient api;
  private UUID owner;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "vita-insights-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(login.ok()).isTrue();
    owner = UUID.fromString(login.json().path("userId").asText());
  }

  private ApiClient signUp() {
    ApiClient c = new ApiClient(port);
    c.prime();
    String email = "vita-insights-" + UUID.randomUUID() + "@jizza.local";
    assertThat(c.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(c.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    return c;
  }

  private static JsonNode ok(ApiClient.Result r) {
    assertThat(r.ok()).as(r.body()).isTrue();
    return r.json();
  }

  private String person(String name) {
    return ok(api.post("/api/people", Map.of("displayName", name, "kind", "parent", "type", "individual"))).path("id").asText();
  }

  @Test
  void theWeeklyNoteNamesItsRowsGoesOnceAndNeverForAClosedRecord() {
    String amma = person("Amma");
    String appa = person("Appa");
    PersonScope p = scopes.person(owner, UUID.fromString(amma));
    LocalDate today = LocalDate.now(INDIA);
    LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    Instant mondayStart = monday.atStartOfDay(INDIA).toInstant();

    // A medicine started on Monday whose first dose (00:05) was not marked. Dose rows are only made
    // from when a schedule is saved, so Monday's row is put in as the nightly run would have.
    JsonNode med = medicines.create(p, Json.obj().put("name", "metformin").put("startDate", monday.toString())
        .set("schedule", Json.obj().set("times", Json.mapper().createArrayNode().add("00:05"))), "manual", mondayStart);
    UUID medId = UUID.fromString(med.path("id").asText());
    UUID scheduleId = UUID.fromString(medicines.schedules(p, medId).get(0).path("id").asText());
    Instant due = monday.atTime(0, 5).atZone(INDIA).toInstant();
    store.insertMissing(owner, List.of(new VitaDoseStore.NewDose(p.personId(), medId, scheduleId, due)));
    reminders.minute(due.plus(Duration.ofMinutes(61)), Set.of(owner));

    // A blood pressure reading above the person's own range, and a visit in three days.
    ok(api.put("/api/vita/people/" + amma + "/ranges", Map.of("kind", "bp", "setting", "home", "high", 130, "high2", 85)));
    Instant at = Instant.now().minus(Duration.ofMinutes(2)).truncatedTo(ChronoUnit.SECONDS);
    if (at.isBefore(mondayStart)) at = mondayStart.plusSeconds(60);
    ok(api.post("/api/vita/people/" + amma + "/measurements", Map.of("kind", "bp", "value", 148, "value2", 94,
        "takenAt", at.toString())));
    String doctor = ok(api.post("/api/vita/doctors", Map.of("name", "Meera Rao"))).path("id").asText();
    ok(api.post("/api/vita/people/" + amma + "/visits", Map.of("doctorId", doctor,
        "scheduledAt", today.plusDays(3) + "T10:30")));

    JsonNode note = ok(api.get("/api/vita/people/" + amma + "/weekly-note"));
    assertThat(note.path("disclaimer").asText()).isEqualTo("Prepared by Vita from your own records. Not medical advice.");
    assertThat(note.path("weekStart").asText()).isEqualTo(monday.toString());
    List<String> kinds = new ArrayList<>();
    for (JsonNode s : note.path("sentences")) {
      kinds.add(s.path("kind").asText());
      assertThat(s.path("rows").size()).as(s.toString()).isGreaterThan(0);
      for (JsonNode r : s.path("rows")) assertThat(r.path("id").asText()).isNotBlank();
    }
    assertThat(kinds).contains("medicine_change", "missed_doses", "outside_range", "visit");
    String text = note.path("sentences").toString();
    assertThat(text).contains("metformin was started on").contains("1 dose was not marked as taken this week.")
        .contains("1 blood pressure reading was outside the usual range: 148/94 on")
        .contains("A visit with Dr Meera Rao is planned for");
    assertThat(text.toLowerCase()).doesNotContain("because").doesNotContain("diagnos");

    // Only the manager (or the person themself) reads the note; another account sees nothing.
    ApiClient other = signUp();
    assertThat(other.get("/api/vita/people/" + amma + "/weekly-note").status()).isEqualTo(404);

    // A closed record gets no note; the job sends Amma's once and keeps it.
    assertThat(api.patch("/api/people/" + appa + "/status", Map.of("status", "deceased", "on", today.toString())).ok()).isTrue();
    var run = notes.weekly(Instant.now(), Set.of(owner));
    assertThat(run.sent()).as("Amma and the account's own record, not Appa").isEqualTo(run.people());
    assertThat(run.people()).isGreaterThanOrEqualTo(1);
    assertThat(notes.weekly(Instant.now(), Set.of(owner)).sent()).as("once a week").isZero();
    JsonNode inbox = api.get("/api/notifications").json();
    assertThat(inbox.toString()).contains("Amma's weekly health note is ready.").doesNotContain("Appa's weekly")
        .doesNotContain("148");
    JsonNode kept = ok(api.get("/api/vita/people/" + amma + "/weekly-note"));
    assertThat(kept.path("sentAt").asText()).isNotBlank();
    assertThat(kept.path("sentences").size()).isEqualTo(note.path("sentences").size());
    assertThat(api.get("/api/vita/people/" + amma + "/weekly-note?weekStart=" + monday.plusDays(1)).status()).isEqualTo(400);
  }

  @Test
  void goesWithStaysSilentBelowTheJournalDaysAndShowsTheDaysBehindAStatement() {
    String mother = person("Mother");
    ok(api.put("/api/vita/config/insights.min_journal_days", Map.of("value", Map.of("days", 6))));
    LocalDate today = LocalDate.now(INDIA);
    String grant = ok(api.post("/api/vita/connectors", Map.of("personId", mother, "source", "health_connect",
        "dataTypes", List.of("sleep")))).path("id").asText();
    List<Map<String, Object>> sleep = new ArrayList<>();
    for (int i = 1; i <= 6; i++) {
      LocalDate day = today.minusDays(7 - i);
      // Mood climbs with each longer night.
      ok(api.put("/api/vita/people/" + mother + "/journal/" + day, Map.of("mood", Math.min(5, i), "energy", 3)));
      Instant woke = day.atTime(7, 0).atZone(INDIA).toInstant();
      sleep.add(Map.of("externalId", "sleep-" + day, "type", "sleep",
          "startedAt", woke.minus(Duration.ofMinutes(240 + 30L * i)).toString(), "endedAt", woke.toString()));
    }
    ok(api.post("/api/vita/ingest", Map.of("personId", mother, "grantId", grant, "records", sleep)));
    JsonNode found = ok(api.get("/api/vita/people/" + mother + "/insights"));
    assertThat(found.path("disclaimer").asText()).isEqualTo("Prepared by Vita from your own records. Not medical advice.");
    assertThat(found.path("silent").asBoolean()).isFalse();
    JsonNode first = found.path("statements").get(0);
    assertThat(first.path("journalField").asText()).isEqualTo("mood");
    assertThat(first.path("factor").asText()).isEqualTo("sleep");
    assertThat(first.path("text").asText()).startsWith("Mood goes with sleep").doesNotContain("cause");
    assertThat(found.path("note").asText()).contains("does not mean one causes the other");
    assertThat(first.path("days").asInt()).isEqualTo(6);
    assertThat(first.path("data")).hasSize(6);
    assertThat(first.path("data").get(0).path("rowIds")).hasSize(2);
    // Energy never changes, so nothing is said about it.
    assertThat(found.path("statements").toString()).doesNotContain("\"journalField\":\"energy\"");

    // Below the configured days, nothing at all.
    ok(api.put("/api/vita/config/insights.min_journal_days", Map.of("value", Map.of("days", 21))));
    JsonNode silent = ok(api.get("/api/vita/people/" + mother + "/insights"));
    assertThat(silent.path("silent").asBoolean()).isTrue();
    assertThat(silent.path("statements")).isEmpty();
    assertThat(silent.path("message").asText()).contains("21 days").contains("it has 6");

    // The journal is private: another account cannot read what goes with it.
    assertThat(signUp().get("/api/vita/people/" + mother + "/insights").status()).isEqualTo(404);
  }
}
