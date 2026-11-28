package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.vita.medicine.VitaMedicineUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.visits.VitaVisitUseCase;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Visit prep, the doctor-ready summary, vaccinations and surgeries/stays (spec 4.2, 5.8, Fixture G):
 * the prep lists the dose change since the last visit from the change history; the summary holds
 * only what is in the person's rows and leaves private things out unless asked; nothing is sent.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaVisitsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  @LocalServerPort private int port;
  @Autowired private VitaMedicineUseCase medicines;
  @Autowired private VitaVisitUseCase visits;
  @Autowired private VitaScopeResolver scopes;
  private ApiClient api;
  private UUID owner;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "vita-visit-" + UUID.randomUUID() + "@jizza.local";
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

  private static ObjectNode medicine(String name, String strength, String start, String time) {
    ObjectNode b = Json.obj().put("name", name).put("startDate", start).put("strength", strength);
    b.putObject("schedule").putArray("times").add(time);
    return b;
  }

  /** Fixture G: telmisartan 40 mg from 1 Dec, changed by Dr S Iyer to 80 mg from 16 Dec. */
  private String fixtureG(PersonScope father) {
    String doctor = api.post("/api/vita/doctors", Map.of("name", "Dr S Iyer")).json().path("id").asText();
    var med = medicines.create(father, medicine("telmisartan", "40 mg", "2026-12-01", "08:00"), "manual",
        ist("2026-11-30T23:00"));
    var change = Json.obj().put("fromDate", "2026-12-16").put("strength", "80 mg").put("doctorId", doctor)
        .put("reason", "Doctor's advice");
    medicines.change(father, UUID.fromString(med.path("id").asText()), change, ist("2026-12-15T10:00"));
    return doctor;
  }

  private static List<String> texts(JsonNode array, String field) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.path(field).asText()));
    return out;
  }

  @Test
  void fixtureG_visitPrepListsTheDoseChangeSinceTheLastVisit() {
    PersonScope father = person("Father");
    String doctor = fixtureG(father);
    String base = "/api/vita/people/" + father.personId() + "/visits";
    // An earlier visit that took place: changes are counted from it.
    var earlier = api.post(base, Map.of("scheduledAt", "2026-11-20T10:00", "status", "done", "doctorId", doctor));
    assertThat(earlier.ok()).as(earlier.body()).isTrue();
    var visit = api.post(base, Map.of("scheduledAt", "2027-01-05T10:30", "doctorId", doctor, "reason", "BP review",
        "questions", List.of("Is the higher dose working?", Map.of("text", "  "), Map.of("text", "Should I check BP at night?"))));
    assertThat(visit.ok()).as(visit.body()).isTrue();
    UUID visitId = UUID.fromString(visit.json().path("id").asText());
    assertThat(visit.json().path("questions")).hasSize(2);

    JsonNode prep = visits.prepare(owner, visitId, ist("2027-01-04T18:00"));
    assertThat(texts(prep.path("medicineChanges"), "line"))
        .containsExactly("1 Dec: telmisartan started", "16 Dec: telmisartan changed from 40 mg to 80 mg (Dr S Iyer)");
    assertThat(prep.path("changesSince").asText()).isEqualTo("2026-11-20");
    assertThat(prep.path("changesSinceLabel").asText()).isEqualTo("since the visit on 20 Nov 2026");
    assertThat(prep.path("questions")).hasSize(2);
    assertThat(prep.path("questions").get(0).asText()).isEqualTo("Is the higher dose working?");
    assertThat(prep.path("doctor").path("name").asText()).isEqualTo("Dr S Iyer");
    assertThat(texts(prep.path("medicines"), "name")).containsExactly("telmisartan");
    assertThat(prep.path("note").asText()).isEqualTo(VitaVisitUseCase.NOTE);
    assertThat(prep.path("sent").asBoolean()).isFalse();

    // Over HTTP it answers the same shape, and another account's visit reads as not found.
    var http = api.post("/api/vita/visits/" + visitId + "/prepare", Map.of());
    assertThat(http.ok()).as(http.body()).isTrue();
    assertThat(http.json().path("sent").asBoolean()).isFalse();
    assertThat(api.post("/api/vita/visits/" + UUID.randomUUID() + "/prepare", Map.of()).status()).isEqualTo(404);
  }

  @Test
  void theSummaryHoldsOnlyWhatIsInThePersonsRows() {
    PersonScope father = person("Father");
    fixtureG(father);
    String base = "/api/vita/people/" + father.personId();
    assertThat(api.post(base + "/conditions", Map.of("name", "High blood pressure", "template", "hypertension",
        "status", "active")).ok()).isTrue();
    assertThat(api.post(base + "/conditions", Map.of("name", "Private matter", "status", "active", "private", true))
        .ok()).isTrue();
    assertThat(api.post(base + "/allergies", Map.of("substance", "Penicillin", "severity", "severe")).ok()).isTrue();

    JsonNode summary = visits.summary(owner, father.personId(), null, false, ist("2027-01-04T18:00"));
    assertThat(texts(summary.path("conditions"), "name")).containsExactly("High blood pressure");
    assertThat(texts(summary.path("allergies"), "substance")).containsExactly("Penicillin");
    assertThat(texts(summary.path("medicines"), "name")).containsExactly("telmisartan");
    assertThat(texts(summary.path("medicineChanges"), "line"))
        .containsExactly("1 Dec: telmisartan started", "16 Dec: telmisartan changed from 40 mg to 80 mg (Dr S Iyer)");
    // No lab report, no readings: nothing is filled in.
    assertThat(summary.path("labs")).isEmpty();
    assertThat(summary.path("readings")).isEmpty();
    assertThat(summary.path("sent").asBoolean()).isFalse();
    assertThat(summary.path("note").asText()).isEqualTo(VitaVisitUseCase.NOTE);

    JsonNode withPrivate = visits.summary(owner, father.personId(), null, true, ist("2027-01-04T18:00"));
    assertThat(texts(withPrivate.path("conditions"), "name")).containsExactlyInAnyOrder("High blood pressure", "Private matter");

    var http = api.get(base + "/summary");
    assertThat(http.ok()).as(http.body()).isTrue();
    assertThat(http.body()).doesNotContain("Private matter");
    assertThat(api.get("/api/vita/people/" + UUID.randomUUID() + "/summary").status()).isEqualTo(404);
  }

  @Test
  void vaccinationsAndStaysCanBeAddedCorrectedAndRemoved() {
    PersonScope mother = person("Mother");
    String base = "/api/vita/people/" + mother.personId();

    var noDate = api.post(base + "/vaccinations", Map.of("vaccine", "Sample vaccine"));
    assertThat(noDate.status()).as(noDate.body()).isEqualTo(400);
    var shot = api.post(base + "/vaccinations", Map.of("vaccine", "Sample vaccine", "doseNumber", 1,
        "givenOn", "2026-03-01", "nextDueOn", "2027-03-01"));
    assertThat(shot.ok()).as(shot.body()).isTrue();
    String shotId = shot.json().path("id").asText();
    assertThat(shot.json().path("source").asText()).isEqualTo("manual");
    var fixed = api.put(base + "/vaccinations/" + shotId, Map.of("batch", "B-123"));
    assertThat(fixed.ok()).as(fixed.body()).isTrue();
    assertThat(fixed.json().path("batch").asText()).isEqualTo("B-123");
    assertThat(api.get(base + "/vaccinations").json()).hasSize(1);

    var backwards = api.post(base + "/events", Map.of("kind", "admission", "fromDate", "2026-02-04", "toDate", "2026-02-01"));
    assertThat(backwards.status()).as(backwards.body()).isEqualTo(400);
    var stay = api.post(base + "/events", Map.of("kind", "admission", "title", "Sample stay", "fromDate", "2026-02-01",
        "toDate", "2026-02-04", "hospital", "Sample Hospital"));
    assertThat(stay.ok()).as(stay.body()).isTrue();
    String stayId = stay.json().path("id").asText();
    var unknownKind = api.post(base + "/events", Map.of("kind", "checkup", "fromDate", "2026-02-01"));
    assertThat(unknownKind.status()).as(unknownKind.body()).isEqualTo(400);
    var older = api.post(base + "/events", Map.of("kind", "fall", "fromDate", "2025-06-01"));
    assertThat(older.ok()).as(older.body()).isTrue();
    // Newest first.
    assertThat(texts(api.get(base + "/events").json(), "id")).containsExactly(stayId, older.json().path("id").asText());

    assertThat(api.delete(base + "/vaccinations/" + shotId).status()).isEqualTo(204);
    assertThat(api.delete(base + "/events/" + stayId).status()).isEqualTo(204);
    assertThat(api.get(base + "/vaccinations").json()).isEmpty();
    assertThat(api.get(base + "/events").json()).hasSize(1);
  }
}
