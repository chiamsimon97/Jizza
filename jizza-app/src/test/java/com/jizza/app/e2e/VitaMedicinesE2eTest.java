package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaDoseStore;
import com.jizza.platform.usecase.vita.medicine.VitaDoseUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaDoseUseCase.Act;
import com.jizza.platform.usecase.vita.medicine.VitaMedicineUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaReminderService;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Vita's medicines and reminders (spec 5.2, Phase 1a part 2) end to end, with the clock passed in
 * so a month runs in seconds: Fixture G stored, Fixture C's stock, refill and missed slot, and the
 * rules of 5.2 (afternoon start, pause, end date, late marking, the first mark wins, snooze cap).
 * Each test uses its own dates and runs the jobs for its own account only.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaMedicinesE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  @LocalServerPort private int port;
  @Autowired private VitaMedicineUseCase medicines;
  @Autowired private VitaDoseUseCase doses;
  @Autowired private VitaReminderService reminders;
  @Autowired private VitaDoseStore store;
  @Autowired private VitaScopeResolver scopes;
  private ApiClient api;
  private UUID owner;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "vita-med-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(login.ok()).isTrue();
    owner = UUID.fromString(login.json().path("userId").asText());
  }

  private PersonScope person(String name) {
    String id = api.post("/api/people", Map.of("displayName", name, "kind", "parent", "type", "individual"))
        .json().path("id").asText();
    return scopes.person(owner, UUID.fromString(id));
  }

  private static Instant ist(String local) {
    return LocalDateTime.parse(local).atZone(INDIA).toInstant();
  }

  private static ObjectNode medicine(String name, String strength, String start, String... times) {
    ObjectNode b = Json.obj().put("name", name).put("startDate", start);
    if (strength != null) b.put("strength", strength);
    var t = b.putObject("schedule").putArray("times");
    for (String x : times) t.add(x);
    return b;
  }

  private static Act taken(Instant at) {
    return new Act("taken", at, null, null);
  }

  private UUID id(JsonNode row) {
    return UUID.fromString(row.path("id").asText());
  }

  private int notifications(String text) {
    int n = 0;
    for (JsonNode note : api.get("/api/notifications").json()) if (note.toString().contains(text)) n++;
    return n;
  }

  @Test
  void fixtureGADoseChangeClosesTheOldScheduleKeepsThePastAndWritesOneEvent() {
    PersonScope father = person("Father");
    String doctor = api.post("/api/vita/doctors", Map.of("name", "Dr S Iyer (sample)")).json().path("id").asText();
    var med = medicines.create(father, medicine("telmisartan", "40 mg", "2026-12-01", "08:00"), "manual",
        ist("2026-11-30T23:00"));
    UUID medId = id(med);
    for (int d = 1; d <= 31; d++) {
      String day = String.format("2026-12-%02d", d);
      medicines.expand(father, ist(day + "T07:00"));
      if (d == 15) {
        var change = Json.obj().put("fromDate", "2026-12-16").put("strength", "80 mg").put("doctorId", doctor)
            .put("reason", "Doctor's advice");
        medicines.change(father, medId, change, ist("2026-12-15T10:00"));
      }
      if (d == 10) {
        reminders.minute(ist(day + "T09:00"), Set.of(owner));
        continue;
      }
      doses.actSlot(father, ist(day + "T08:00"), taken(ist(day + "T08:05")), ist(day + "T08:05"), true);
    }

    var schedules = medicines.schedules(father, medId);
    assertThat(schedules).hasSize(2);
    assertThat(schedules.get(0).path("effectiveFrom").asText()).isEqualTo("2026-12-01");
    assertThat(schedules.get(0).path("effectiveTo").asText()).isEqualTo("2026-12-15");
    assertThat(schedules.get(1).path("effectiveFrom").asText()).isEqualTo("2026-12-16");
    assertThat(schedules.get(1).path("effectiveTo").isNull()).isTrue();
    UUID oldId = id(schedules.get(0));
    var december = store.forPerson(owner, father.personId(), ist("2026-12-01T00:00"), ist("2027-01-01T00:00"));
    assertThat(december).hasSize(31);
    assertThat(december.stream().filter(r -> r.scheduleId().equals(oldId)).count()).isEqualTo(15);
    assertThat(december.stream().filter(r -> r.scheduleId().equals(oldId)).allMatch(r -> r.dueAt().isBefore(ist("2026-12-16T00:00"))))
        .isTrue();

    var events = medicines.events(owner, father.personId(), medId);
    var changed = events.stream().filter(e -> "dose_changed".equals(e.path("kind").asText())).toList();
    assertThat(changed).hasSize(1);
    assertThat(changed.get(0).path("onDate").asText()).isEqualTo("2026-12-16");
    assertThat(changed.get(0).path("fromText").asText()).isEqualTo("40 mg");
    assertThat(changed.get(0).path("toText").asText()).isEqualTo("80 mg");
    assertThat(changed.get(0).path("doctorId").asText()).isEqualTo(doctor);
    assertThat(medicines.medicine(father, medId).path("strength").asText()).isEqualTo("80 mg");

    var first = doses.adherence(father, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 15)).path("overall");
    assertThat(first.path("missed").asInt()).isEqualTo(1);
    assertThat(first.path("percent").asInt()).isEqualTo(93);
    assertThat(first.path("exact").decimalValue()).isEqualByComparingTo("93.33");
    var second = doses.adherence(father, LocalDate.of(2026, 12, 16), LocalDate.of(2026, 12, 31)).path("overall");
    assertThat(second.path("taken").asInt()).isEqualTo(16);
    assertThat(second.path("percent").asInt()).isEqualTo(100);
    var all = doses.adherence(father, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)).path("overall");
    assertThat(all.path("due").asInt()).isEqualTo(31);
    assertThat(all.path("percent").asInt()).isEqualTo(97);
  }

  @Test
  void fixtureCStockFallsWithEachDoseOneRefillReminderAfterDose31AndARefillOf30() {
    PersonScope mother = person("Mother");
    var body = medicine("Metformin 500 mg", "500 mg", "2026-12-01", "08:00", "20:00")
        .put("trackStock", true).put("stockUnits", 45).put("unitsPerPack", 30).put("refillLeadDays", 7);
    ((ObjectNode) body.get("schedule")).put("withFood", "after");
    UUID med = id(medicines.create(mother, body, "manual", ist("2026-11-30T23:00")));
    int dose = 0;
    for (int d = 1; d <= 17; d++) {
      String day = String.format("2026-12-%02d", d);
      medicines.expand(mother, ist(day + "T07:00"));
      for (String t : List.of("08:00", "20:00")) {
        if (d == 17 && t.equals("20:00")) break;
        dose++;
        doses.actSlot(mother, ist(day + "T" + t), taken(ist(day + "T" + t)), ist(day + "T" + t).plusSeconds(60), true);
        var row = medicines.medicine(mother, med);
        if (dose == 30) {
          assertThat(row.path("stockUnits").decimalValue()).isEqualByComparingTo("15");
          assertThat(row.hasNonNull("refillRemindedAt")).as("no reminder at 15").isFalse();
        }
        if (dose == 31) {
          assertThat(row.path("stockUnits").decimalValue()).isEqualByComparingTo("14");
          assertThat(row.hasNonNull("refillRemindedAt")).as("the reminder after dose 31, 16 Dec 08:00").isTrue();
        }
      }
    }
    assertThat(dose).isEqualTo(33);
    assertThat(notifications("Mother is running low on")).as("one refill reminder, no name").isEqualTo(1);
    assertThat(api.get("/api/notifications").body()).doesNotContain("Metformin");
    assertThat(medicines.medicine(mother, med).path("stockUnits").decimalValue()).isEqualByComparingTo("12");

    // C1b: a pack of 30 is recorded.
    var refilled = api.post("/api/vita/people/" + mother.personId() + "/medicines/" + med + "/refill", Map.of("units", 30));
    assertThat(refilled.ok()).as(refilled.body()).isTrue();
    assertThat(refilled.json().path("stockUnits").decimalValue()).isEqualByComparingTo("42");
    assertThat(refilled.json().path("refillDue").asBoolean()).isFalse();
    var events = api.get("/api/vita/people/" + mother.personId() + "/medicines/" + med + "/events").json();
    assertThat(events.toString()).contains("\"kind\":\"refilled\"").contains("\"fromText\":\"12\"").contains("\"toText\":\"42\"");
    var refillReminders = api.get("/api/vita/people/" + mother.personId() + "/reminders").json();
    assertThat(refillReminders.toString()).contains("\"kind\":\"refill\"").contains("\"status\":\"done\"");
  }

  @Test
  void fixtureCTheMissedSlotIsMarkedAt0900AndOneAlertGoesToTheManager() {
    PersonScope mother = person("Amma");
    medicines.create(mother, medicine("metformin", null, "2026-12-01", "08:00", "20:00"), "manual",
        ist("2026-11-30T23:00"));
    medicines.expand(mother, ist("2026-12-09T07:00"));
    Set<UUID> me = Set.of(owner);

    var at8 = reminders.minute(ist("2026-12-09T08:00"), me);
    assertThat(at8.slotReminders()).isEqualTo(1);
    assertThat(notifications("Amma · 8:00 medicines")).isEqualTo(1);
    assertThat(api.get("/api/notifications").body()).doesNotContain("metformin");
    assertThat(reminders.minute(ist("2026-12-09T08:01"), me).slotReminders()).as("one reminder per slot").isZero();
    assertThat(reminders.minute(ist("2026-12-09T08:59"), me).missedDoses()).isZero();

    var at9 = reminders.minute(ist("2026-12-09T09:00"), me);
    assertThat(at9.missedDoses()).isEqualTo(1);
    assertThat(at9.missedAlerts()).isEqualTo(1);
    assertThat(reminders.minute(ist("2026-12-09T09:01"), me).missedAlerts()).isZero();
    assertThat(notifications("Amma · 8:00 medicines were not marked as taken.")).isEqualTo(1);
    var slot = doses.slotView(mother, ist("2026-12-09T08:00"), ist("2026-12-09T09:02"), true);
    assertThat(slot.path("status").asText()).isEqualTo("missed");
  }

  @Test
  void aMedicineAddedInTheAfternoonDoesNotStartWithAMissedMorningDose() {
    PersonScope p = person("Father");
    LocalDateTime nowIst = LocalDateTime.now(INDIA);
    org.junit.jupiter.api.Assumptions.assumeTrue(nowIst.getHour() >= 1, "too close to midnight in India");
    String earlier = nowIst.minusMinutes(30).toLocalTime().withSecond(0).withNano(0).toString().substring(0, 5);
    var made = api.post("/api/vita/people/" + p.personId() + "/medicines",
        Json.obj().put("name", "amlodipine").set("schedule", Json.obj().set("times", Json.mapper().createArrayNode().add(earlier))));
    assertThat(made.ok()).as(made.body()).isTrue();
    var today = api.get("/api/vita/people/" + p.personId() + "/doses").json();
    assertThat(today.path("slots").toString()).doesNotContain("\"localTime\":\"" + earlier + "\"");
    var tomorrow = api.get("/api/vita/people/" + p.personId() + "/doses?from=" + nowIst.toLocalDate().plusDays(1)).json();
    assertThat(tomorrow.path("slots")).hasSize(1);
  }

  @Test
  void aPausedMedicineMakesNoDosesUntilItIsResumed() {
    PersonScope p = person("Father");
    UUID med = id(medicines.create(p, medicine("aspirin", "75 mg", "2027-03-01", "08:00"), "manual", ist("2027-03-01T00:30")));
    assertThat(store.forPerson(owner, p.personId(), ist("2027-03-01T00:00"), ist("2027-03-04T00:00"))).hasSize(2);
    medicines.pause(p, med, Json.obj().put("reason", "Doctor said to pause"), ist("2027-03-01T00:40"));
    assertThat(store.forPerson(owner, p.personId(), ist("2027-03-01T00:00"), ist("2027-03-04T00:00"))).isEmpty();
    assertThat(medicines.expand(p, ist("2027-03-02T00:30"))).isZero();
    medicines.resume(p, med, null, ist("2027-03-02T00:30"));
    assertThat(store.forPerson(owner, p.personId(), ist("2027-03-01T00:00"), ist("2027-03-05T00:00"))).hasSize(2);
    var kinds = medicines.events(owner, p.personId(), med).stream().map(e -> e.path("kind").asText()).toList();
    assertThat(kinds).containsExactly("started", "paused", "resumed");
  }

  @Test
  void aMedicinePastItsEndDateStops() {
    PersonScope p = person("Father");
    UUID med = id(medicines.create(p, medicine("amoxicillin", "500 mg", "2027-03-10", "08:00").put("endDate", "2027-03-12"),
        "manual", ist("2027-03-10T00:30")));
    medicines.expand(p, ist("2027-03-11T07:00"));
    var rows = store.forPerson(owner, p.personId(), ist("2027-03-10T00:00"), ist("2027-03-20T00:00"));
    assertThat(rows).hasSize(3);
    reminders.nightly(ist("2027-03-13T00:45"), Set.of(owner));
    var row = medicines.medicine(p, med);
    assertThat(row.path("status").asText()).isEqualTo("stopped");
    var stopped = medicines.events(owner, p.personId(), med).stream().filter(e -> "stopped".equals(e.path("kind").asText())).toList();
    assertThat(stopped).hasSize(1);
    assertThat(stopped.get(0).path("onDate").asText()).isEqualTo("2027-03-12");
    assertThat(medicines.expand(p, ist("2027-03-13T07:00"))).isZero();
  }

  @Test
  void aDoseMarkedTheNextDayCountsAsTakenAndLate() {
    PersonScope p = person("Father");
    medicines.create(p, medicine("telmisartan", null, "2027-04-01", "08:00"), "manual", ist("2027-04-01T00:30"));
    reminders.minute(ist("2027-04-01T09:00"), Set.of(owner));
    var row = store.slot(owner, p.personId(), ist("2027-04-01T08:00")).get(0);
    assertThat(row.status()).isEqualTo("missed");
    var slot = doses.actDose(owner, row.id(), taken(ist("2027-04-01T08:10")), ist("2027-04-02T07:30"), true);
    assertThat(slot.path("status").asText()).isEqualTo("taken");
    var d = slot.path("doses").get(0);
    assertThat(d.path("late").asBoolean()).isTrue();
    assertThat(Instant.parse(d.path("takenAt").asText())).isEqualTo(ist("2027-04-01T08:10"));
    assertThat(Instant.parse(d.path("actedAt").asText())).isEqualTo(ist("2027-04-02T07:30"));
    var a = doses.adherence(p, LocalDate.of(2027, 4, 1), LocalDate.of(2027, 4, 1)).path("overall");
    assertThat(a.path("taken").asInt()).isEqualTo(1);
    assertThat(a.path("late").asInt()).isEqualTo(1);
    assertThat(a.path("percent").asInt()).isEqualTo(100);
  }

  @Test
  void whenTwoPeopleMarkOneSlotTheFirstWinsAndTheSecondSeesWho() {
    PersonScope p = person("Mother");
    medicines.create(p, medicine("metformin", null, "2027-04-10", "08:00", "20:00"), "manual", ist("2027-04-10T00:30"));
    var row = store.slot(owner, p.personId(), ist("2027-04-10T08:00")).get(0);
    var first = doses.actSlot(p, ist("2027-04-10T08:00"), taken(null), ist("2027-04-10T08:02"), true);
    assertThat(first.path("status").asText()).isEqualTo("taken");
    // The second press was made on the row as it was before the first (an old version).
    assertThat(store.mark(owner, row.id(), row.version(), new VitaDoseStore.Mark("skipped", null, null,
        ist("2027-04-10T08:03"), owner, null, null))).isFalse();
    var second = doses.actSlot(p, ist("2027-04-10T08:00"), new Act("skipped", null, null, null), ist("2027-04-10T08:03"), true);
    assertThat(second.path("status").asText()).isEqualTo("taken");
    assertThat(second.path("markedBy").asText()).isEqualTo(owner.toString());
    assertThat(Instant.parse(second.path("markedAt").asText())).isEqualTo(ist("2027-04-10T08:02"));
  }

  @Test
  void aSnoozeStopsAtTheTimeTheSlotWouldBeMissed() {
    PersonScope p = person("Mother");
    medicines.create(p, medicine("metformin", null, "2027-04-20", "08:00"), "manual", ist("2027-04-20T00:30"));
    var slot = doses.actSlot(p, ist("2027-04-20T08:00"), new Act("snoozed", null, 15, null), ist("2027-04-20T08:55"), true);
    assertThat(slot.path("status").asText()).isEqualTo("snoozed");
    assertThat(Instant.parse(slot.path("snoozedUntil").asText())).isEqualTo(ist("2027-04-20T09:00"));
    assertThat(reminders.minute(ist("2027-04-20T09:00"), Set.of(owner)).missedDoses()).isEqualTo(1);
  }

  @Test
  void theRoutesAddCorrectListAndActAndKeepTheNameAsWritten() {
    PersonScope p = person("Father");
    String base = "/api/vita/people/" + p.personId();
    var noTimes = api.post(base + "/medicines", Map.of("name", "Telma"));
    assertThat(noTimes.status()).isEqualTo(400);
    var badTime = api.post(base + "/medicines", Map.of("name", "Telma", "schedule", Map.of("times", List.of("8am"))));
    assertThat(badTime.status()).isEqualTo(400);
    var made = api.post(base + "/medicines", Map.of("name", "  Telma 40 (white)  ", "form", "tablet",
        "schedule", Map.of("times", List.of("20:00", "08:00"), "withFood", "after")));
    assertThat(made.ok()).as(made.body()).isTrue();
    assertThat(made.json().path("name").asText()).isEqualTo("Telma 40 (white)");
    assertThat(made.json().path("schedule").path("times").toString()).isEqualTo("[\"08:00\",\"20:00\"]");
    String med = made.json().path("id").asText();
    var asNeeded = api.post(base + "/medicines", Map.of("name", "paracetamol", "asNeeded", true));
    assertThat(asNeeded.ok()).as(asNeeded.body()).isTrue();
    assertThat(asNeeded.json().path("schedule").isNull()).isTrue();

    assertThat(api.get(base + "/medicines").json()).hasSize(2);
    var put = api.put(base + "/medicines/" + med, Map.of("notes", "White tablet"));
    assertThat(put.ok()).as(put.body()).isTrue();
    assertThat(api.put(base + "/medicines/" + med, Map.of("schedule", Map.of("times", List.of("09:00")))).status()).isEqualTo(400);
    var change = api.post(base + "/medicines/" + med + "/change", Map.of("schedule", Map.of("doseUnits", 2)));
    assertThat(change.ok()).as(change.body()).isTrue();
    var events = api.get(base + "/medicines/" + med + "/events").json();
    assertThat(events.toString()).contains("1 tablet at 08:00, 20:00").contains("2 tablets at 08:00, 20:00");
    assertThat(api.post(base + "/medicines/" + med + "/change", Map.of("schedule", Map.of("doseUnits", 2))).status())
        .as("nothing to change").isEqualTo(400);

    var tomorrow = LocalDate.now(INDIA).plusDays(1);
    var list = api.get(base + "/doses?from=" + tomorrow + "&to=" + tomorrow).json();
    assertThat(list.path("slots")).hasSize(2);
    String dueAt = list.path("slots").get(0).path("dueAt").asText();
    assertThat(list.path("slots").get(0).path("doses").get(0).path("medicineName").asText()).isEqualTo("Telma 40 (white)");
    var skip = api.post(base + "/slots/" + dueAt + "/act", Map.of("action", "skipped"));
    assertThat(skip.ok()).as(skip.body()).isTrue();
    assertThat(skip.json().path("status").asText()).isEqualTo("skipped");
    assertThat(api.post(base + "/slots/" + dueAt + "/act", Map.of("action", "eaten")).status()).isEqualTo(400);
    String dose = list.path("slots").get(1).path("doses").get(0).path("id").asText();
    assertThat(api.post("/api/vita/doses/" + dose + "/act", Map.of("action", "taken")).status())
        .isEqualTo(200);
    var adherence = api.get(base + "/adherence?from=" + tomorrow + "&to=" + tomorrow).json();
    assertThat(adherence.path("overall").path("due").asInt()).isEqualTo(2);
    assertThat(adherence.path("overall").path("skipped").asInt()).isEqualTo(1);

    var reminder = api.post(base + "/reminders", Map.of("kind", "custom", "title", "Book the eye check", "dueAt", "2030-01-01T09:00",
        "recurrence", "FREQ=YEARLY"));
    assertThat(reminder.ok()).as(reminder.body()).isTrue();
    assertThat(api.post(base + "/reminders", Map.of("kind", "custom", "dueAt", "2030-01-01T09:00", "recurrence", "yearly")).status())
        .isEqualTo(400);
    assertThat(api.post(base + "/reminders", Map.of("kind", "refill", "dueAt", "2030-01-01T09:00")).status())
        .as("refills come from stock only").isEqualTo(400);
    String rid = reminder.json().path("id").asText();
    assertThat(api.put(base + "/reminders/" + rid, Map.of("status", "dismissed")).ok()).isTrue();
    assertThat(api.delete(base + "/reminders/" + rid).ok()).isTrue();

    var stop = api.post(base + "/medicines/" + med + "/stop", Map.of("reason", "Course done"));
    assertThat(stop.ok()).as(stop.body()).isTrue();
    assertThat(stop.json().path("status").asText()).isEqualTo("stopped");
    var home = api.get(base + "/home").json();
    assertThat(home.has("slots")).isTrue();
    assertThat(home.has("reminders")).isTrue();
  }

  @Test
  void aVisitGetsOneReminderADayBeforeAndItGoesOutOnce() {
    PersonScope p = person("Father");
    var visit = api.post("/api/vita/people/" + p.personId() + "/visits", Map.of("scheduledAt", "2030-05-10T10:30", "reason", "Review"));
    assertThat(visit.ok()).as(visit.body()).isTrue();
    reminders.nightly(ist("2030-05-01T00:45"), Set.of(owner));
    reminders.nightly(ist("2030-05-02T00:45"), Set.of(owner));
    var list = api.get("/api/vita/people/" + p.personId() + "/reminders").json();
    assertThat(list).hasSize(1);
    assertThat(Instant.parse(list.get(0).path("dueAt").asText().replace("+00:00", "Z"))).isEqualTo(ist("2030-05-09T10:30"));
    assertThat(reminders.minute(ist("2030-05-09T10:30"), Set.of(owner)).otherReminders()).isEqualTo(1);
    assertThat(reminders.minute(ist("2030-05-09T10:31"), Set.of(owner)).otherReminders()).isZero();
    assertThat(notifications("Father: a doctor's visit is coming up on 10 May.")).isEqualTo(1);
  }
}
