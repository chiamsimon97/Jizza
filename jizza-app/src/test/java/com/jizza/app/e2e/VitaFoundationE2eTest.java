package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Vita Health, Phase 0 through the API: the shared people list, a person's profile with the ABHA
 * number kept secret, emergency contacts and the emergency card, conditions and allergies with
 * "None known", weight readings, Quick start step by step, Settings with its three figure states,
 * a closed record, and the vita section of the data export.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaFoundationE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @org.springframework.beans.factory.annotation.Autowired
  private com.jizza.platform.usecase.vita.tools.VitaToolProvider tools;

  private ApiClient api;
  private String me;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "vita-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    var people = api.get("/api/people");
    assertThat(people.ok()).as(people.body()).isTrue();
    me = people.json().get(0).path("id").asText();
  }

  private String addParent(String name) {
    var p = api.post("/api/people", Map.of("displayName", name, "kind", "parent", "type", "individual",
        "dob", "1956-03-14"));
    assertThat(p.ok()).as(p.body()).isTrue();
    return p.json().path("id").asText();
  }

  @Test
  void statusSaysOnAndOnlyVitaGetsTheHealthToolsAllRegistered() {
    assertThat(api.get("/api/vita/status").json().path("enabled").asBoolean()).isTrue();
    for (JsonNode agent : api.get("/api/agents").json()) {
      UUID id = UUID.fromString(agent.path("id").asText());
      var names = tools.toolsFor(id).stream().map(t -> t.name()).toList();
      if ("health".equals(agent.path("key").asText())) {
        assertThat(names).containsExactlyInAnyOrder("vita_overview", "vita_read", "vita_red_flag_help",
            "vita_open_screen", "vita_show_form", "vita_trend", "vita_condition_summary",
            "vita_extract_document");
        for (String name : names) {
          assertThat(tools.actionTypeOf(id, name)).as("action type of %s", name).isPresent();
          assertThat(tools.redactArguments(name)).isTrue();
        }
        assertThat(tools.actionTypeOf(id, "vita_read").orElseThrow().name()).isEqualTo("READ");
        assertThat(tools.actionTypeOf(id, "vita_show_form").orElseThrow().name()).isEqualTo("DRAFT");
      } else {
        assertThat(names).as("health tools for %s", agent.path("key").asText()).isEmpty();
      }
    }
  }

  @Test
  void theProfileKeepsTheAbhaNumberSecretAndTheDateOfBirthOnThePerson() {
    String amma = addParent("Amma");
    var saved = api.put("/api/vita/people/" + amma + "/profile", Map.of("bloodGroup", "b_pos",
        "heightCm", 152.5, "sexAtBirth", "female", "abhaNumber", "12-3456-7890-1234", "dob", "1955-01-20",
        "preferredHospital", "City Hospital", "preferredHospitalPhone", "+91 80 1234 5678"));
    assertThat(saved.ok()).as(saved.body()).isTrue();
    JsonNode profile = saved.json().path("profile");
    assertThat(profile.path("bloodGroup").asText()).isEqualTo("b_pos");
    assertThat(profile.path("heightCm").decimalValue()).isEqualByComparingTo("152.5");
    assertThat(profile.path("abhaLast4").asText()).isEqualTo("1234");
    assertThat(saved.body()).doesNotContain("abhaNumberEnc").doesNotContain("12345678901234");
    assertThat(saved.json().path("person").path("dob").asText()).isEqualTo("1955-01-20");
    assertThat(api.get("/api/people/" + amma).json().path("dob").asText()).isEqualTo("1955-01-20");

    assertThat(api.put("/api/vita/people/" + amma + "/profile", Map.of("abhaNumber", "123")).status()).isEqualTo(400);
    assertThat(api.put("/api/vita/people/" + amma + "/profile", Map.of("heightCm", 1820)).status()).isEqualTo(400);
    assertThat(api.put("/api/vita/people/" + amma + "/profile", Map.of("bloodGroup", "O+")).status()).isEqualTo(400);
    assertThat(api.put("/api/vita/people/" + amma + "/profile", Map.of("pan", "x")).status()).isEqualTo(400);
  }

  @Test
  void anHufHasNoHealthRecord() {
    var huf = api.post("/api/people", Map.of("displayName", "Family HUF", "kind", "huf", "type", "huf"));
    assertThat(huf.ok()).as(huf.body()).isTrue();
    String id = huf.json().path("id").asText();
    assertThat(api.get("/api/vita/people/" + id + "/profile").status()).isEqualTo(404);
    assertThat(api.get("/api/people?forHealth=true").body()).doesNotContain(id);
    assertThat(api.get("/api/people").body()).contains(id);
  }

  @Test
  void theEmergencyCardGathersTheRecordSevereAllergiesFirst() {
    String appa = addParent("Appa");
    api.put("/api/vita/people/" + appa + "/profile", Map.of("bloodGroup", "o_pos", "organDonor", true,
        "preferredHospital", "City Hospital"));
    api.post("/api/vita/people/" + appa + "/allergies", Map.of("substance", "Dust", "severity", "mild"));
    api.post("/api/vita/people/" + appa + "/allergies", Map.of("substance", "Penicillin", "category", "drug",
        "severity", "severe", "reaction", "Rash and swelling"));
    api.post("/api/vita/people/" + appa + "/conditions", Map.of("name", "High blood pressure", "template", "hypertension"));
    api.post("/api/vita/people/" + appa + "/conditions", Map.of("name", "Something private", "private", true));
    api.post("/api/vita/people/" + appa + "/emergency-contacts", Map.of("name", "Ravi", "relation", "Son",
        "phone", "+91 98000 00001"));
    api.post("/api/vita/people/" + appa + "/medicines", Map.of("name", "Telma", "strength", "40 mg",
        "schedule", Map.of("times", java.util.List.of("08:00"))));
    api.post("/api/vita/people/" + appa + "/medicines", Map.of("name", "Private pill", "private", true, "asNeeded", true));

    JsonNode card = api.get("/api/vita/people/" + appa + "/emergency-card").json();
    assertThat(card.path("name").asText()).isEqualTo("Appa");
    assertThat(card.path("age").asInt()).isGreaterThan(60);
    assertThat(card.path("bloodGroup").asText()).isEqualTo("o_pos");
    assertThat(card.path("allergies").get(0).path("substance").asText()).isEqualTo("Penicillin");
    assertThat(card.path("conditions")).hasSize(1);
    assertThat(card.toString()).doesNotContain("Something private");
    assertThat(card.path("emergencyContacts").get(0).path("relation").asText()).isEqualTo("Son");
    assertThat(card.path("emergencyNumbers").path("emergency").asText()).isEqualTo("112");
    assertThat(card.path("emergencyNumbersNeedVerification").asBoolean()).isTrue();
    assertThat(card.path("medicinesAvailable").asBoolean()).isTrue();
    assertThat(card.path("medicines")).hasSize(1);
    assertThat(card.path("medicines").get(0).path("name").asText()).isEqualTo("Telma");
    assertThat(card.path("medicines").get(0).path("strength").asText()).isEqualTo("40 mg");
    assertThat(card.toString()).doesNotContain("Private pill");
    assertThat(card.path("updatedOn").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
  }

  @Test
  void noneKnownAndAListAreNeverBothTrue() {
    String amma = addParent("Amma");
    assertThat(api.put("/api/vita/people/" + amma + "/profile", Map.of("allergiesNoneKnown", true)).ok()).isTrue();
    var allergy = api.post("/api/vita/people/" + amma + "/allergies", Map.of("substance", "Peanuts", "category", "food"));
    assertThat(allergy.ok()).as(allergy.body()).isTrue();
    assertThat(api.get("/api/vita/people/" + amma + "/profile").json().path("profile")
        .path("allergiesNoneKnown").asBoolean()).isFalse();
    assertThat(api.put("/api/vita/people/" + amma + "/profile", Map.of("allergiesNoneKnown", true)).status())
        .isEqualTo(400);

    String id = allergy.json().path("id").asText();
    assertThat(api.delete("/api/vita/people/" + amma + "/allergies/" + id).ok()).isTrue();
    assertThat(api.get("/api/vita/people/" + amma + "/allergies").json()).isEmpty();
    assertThat(api.put("/api/vita/people/" + amma + "/profile", Map.of("allergiesNoneKnown", true)).ok()).isTrue();
    assertThat(api.get("/api/vita/people/" + amma + "/emergency-card").json().path("allergiesNoneKnown").asBoolean())
        .isTrue();
  }

  @Test
  void contactsAreLimitedToFive() {
    for (int i = 0; i < 5; i++) {
      assertThat(api.post("/api/vita/people/" + me + "/emergency-contacts",
          Map.of("name", "Contact " + i, "phone", "98000 0000" + i)).ok()).isTrue();
    }
    assertThat(api.post("/api/vita/people/" + me + "/emergency-contacts",
        Map.of("name", "Sixth", "phone", "98000 00009")).status()).isEqualTo(400);
    assertThat(api.post("/api/vita/people/" + me + "/emergency-contacts",
        Map.of("name", "Bad phone", "phone", "call me")).status()).isEqualTo(400);
  }

  @Test
  void weightIsStoredInKilogramsASlipIsRefusedAndAReadingCanMove() {
    var kg = api.post("/api/vita/people/" + me + "/measurements", Map.of("kind", "weight", "value", 72.4,
        "takenAt", "2026-11-20T08:00"));
    assertThat(kg.ok()).as(kg.body()).isTrue();
    assertThat(kg.json().path("unit").asText()).isEqualTo("kg");
    var lb = api.post("/api/vita/people/" + me + "/measurements", Map.of("kind", "weight", "value", 160, "unit", "lb"));
    assertThat(lb.json().path("value").decimalValue()).isEqualByComparingTo("72.575");
    assertThat(lb.json().path("detail").path("typedUnit").asText()).isEqualTo("lb");

    assertThat(api.post("/api/vita/people/" + me + "/measurements", Map.of("kind", "weight", "value", 7240)).status())
        .isEqualTo(400);
    assertThat(api.post("/api/vita/people/" + me + "/measurements", Map.of("kind", "bp", "value", 130)).status())
        .isEqualTo(400);
    assertThat(api.post("/api/vita/people/" + me + "/measurements",
        Map.of("kind", "weight", "value", 70, "takenAt", "2099-01-01T08:00")).status()).isEqualTo(400);

    String id = kg.json().path("id").asText();
    var fixed = api.put("/api/vita/people/" + me + "/measurements/" + id, Map.of("value", 72.1));
    assertThat(fixed.json().path("value").decimalValue()).isEqualByComparingTo("72.1");

    String amma = addParent("Amma");
    var moved = api.post("/api/vita/people/" + me + "/measurements/" + id + "/move", Map.of("toPersonId", amma));
    assertThat(moved.ok()).as(moved.body()).isTrue();
    assertThat(api.get("/api/vita/people/" + me + "/measurements?kind=weight").json()).hasSize(1);
    assertThat(api.get("/api/vita/people/" + amma + "/measurements").json()).hasSize(1);

    assertThat(api.delete("/api/vita/people/" + amma + "/measurements/" + id).ok()).isTrue();
    assertThat(api.get("/api/vita/people/" + amma + "/measurements").json()).isEmpty();
  }

  @Test
  void quickStartSavesEachStepCanSkipAndResumes() {
    String amma = addParent("Amma");
    JsonNode start = api.get("/api/vita/quick-start?personId=" + amma).json();
    assertThat(start.path("progress").path("next").asText()).isEqualTo("dob");

    step(amma, "dob", Map.of("dob", "1956-03-14"));
    api.post("/api/vita/quick-start", Map.of("personId", amma, "step", "sex", "skip", true));
    step(amma, "height_weight", Map.of("heightCm", 150, "weightKg", 61.2));
    step(amma, "blood_group", Map.of("bloodGroup", "a_pos"));
    step(amma, "conditions", Map.of("conditions", List.of(Map.of("name", "Type 2 diabetes", "template", "diabetes_t2"))));

    // Resume: the next step is the first one neither answered nor skipped.
    JsonNode mid = api.get("/api/vita/quick-start?personId=" + amma).json().path("progress");
    assertThat(mid.path("next").asText()).isEqualTo("allergies");
    assertThat(mid.path("skipped").toString()).contains("sex");

    // Answering a step again replaces what it added the first time.
    step(amma, "conditions", Map.of("conditions", List.of(Map.of("name", "Type 2 diabetes", "template", "diabetes_t2"),
        Map.of("name", "Thyroid"))));
    assertThat(api.get("/api/vita/people/" + amma + "/conditions").json()).hasSize(2);
    step(amma, "height_weight", Map.of("heightCm", 150, "weightKg", 61.0));
    assertThat(api.get("/api/vita/people/" + amma + "/measurements").json()).hasSize(1);

    step(amma, "allergies", Map.of("noneKnown", true));
    assertThat(api.post("/api/vita/quick-start", Map.of("personId", amma, "step", "medicines",
        "answers", Map.of("names", List.of("Metformin")))).status()).isEqualTo(400);
    step(amma, "medicines", Map.of("later", true));
    JsonNode done = step(amma, "contact", Map.of("name", "Ravi", "relation", "Son", "phone", "+91 98000 00001"));
    assertThat(done.path("progress").path("done").asBoolean()).isTrue();

    JsonNode profile = api.get("/api/vita/people/" + amma + "/profile").json();
    assertThat(profile.path("profile").path("bloodGroup").asText()).isEqualTo("a_pos");
    assertThat(profile.path("profile").path("allergiesNoneKnown").asBoolean()).isTrue();
    assertThat(api.get("/api/vita/people/" + amma + "/conditions").json().get(0).path("source").asText())
        .isEqualTo("quick_start");
    assertThat(api.get("/api/vita/quick-start").json().path("personId").asText()).isEqualTo(me);
  }

  private JsonNode step(String person, String step, Map<String, Object> answers) {
    var r = api.post("/api/vita/quick-start", Map.of("personId", person, "step", step, "answers", answers));
    assertThat(r.ok()).as("%s: %s", step, r.body()).isTrue();
    return r.json();
  }

  @Test
  void settingsShowVerifiedUnverifiedAndNotSetFigures() {
    JsonNode items = api.get("/api/vita/config").json();
    JsonNode bp = find(items, "clinical.redflags.bp");
    assertThat(bp.path("state").asText()).isEqualTo("needs_verification");
    assertThat(bp.path("needsVerification").asBoolean()).isTrue();
    assertThat(bp.path("sourceUrl").asText()).contains("American Heart Association");
    assertThat(bp.path("value").path("systolicAbove").asInt()).isEqualTo(180);

    JsonNode high = find(items, "clinical.redflags.high_sugar");
    assertThat(high.path("state").asText()).isEqualTo("not_set");
    assertThat(high.path("value").isNull()).isTrue();
    assertThat(high.path("label").asText()).isEqualTo("Not set: ask your doctor");
    assertThat(api.put("/api/vita/config/clinical.redflags.high_sugar", Map.of("value", Map.of("above", 300))).status())
        .isEqualTo(400);

    assertThat(find(items, "display.units").path("state").asText()).isEqualTo("product_default");

    var mine = api.put("/api/vita/config/emergency.numbers",
        Map.of("value", Map.of("emergency", "112", "ambulanceWhereTheyLive", "108")));
    assertThat(mine.ok()).as(mine.body()).isTrue();
    assertThat(mine.json().path("source").asText()).isEqualTo("you");
    assertThat(api.put("/api/vita/config/emergency.numbers", Map.of("value", Map.of("emergency", 112))).status())
        .isEqualTo(400);
    var reset = api.delete("/api/vita/config/emergency.numbers");
    assertThat(reset.json().path("value").path("ambulanceWhereTheyLive").asText()).isEmpty();
    assertThat(api.get("/api/vita/config/nothing.here").status()).isIn(404, 405);
  }

  @Test
  void aClosedRecordIsReadOnlyButStillReadable() {
    String appa = addParent("Appa");
    api.put("/api/vita/people/" + appa + "/profile", Map.of("bloodGroup", "o_pos"));
    var closed = api.patch("/api/people/" + appa + "/status", Map.of("status", "deceased", "on", "2026-11-01"));
    assertThat(closed.ok()).as(closed.body()).isTrue();
    assertThat(closed.json().path("statusOn").asText()).isEqualTo("2026-11-01");

    assertThat(api.get("/api/vita/people/" + appa + "/profile").ok()).isTrue();
    assertThat(api.get("/api/vita/people/" + appa + "/emergency-card").json().path("closed").asBoolean()).isTrue();
    var refused = api.put("/api/vita/people/" + appa + "/profile", Map.of("bloodGroup", "a_pos"));
    assertThat(refused.status()).isEqualTo(400);
    assertThat(refused.body()).contains("closed");

    assertThat(api.patch("/api/people/" + me + "/status", Map.of("status", "archived")).status()).isEqualTo(400);
    assertThat(api.patch("/api/people/" + appa + "/status", Map.of("status", "handed_over")).status()).isEqualTo(400);
    assertThat(api.patch("/api/people/" + appa + "/status", Map.of("status", "active")).ok()).isTrue();
    assertThat(api.put("/api/vita/people/" + appa + "/profile", Map.of("bloodGroup", "a_pos")).ok()).isTrue();
  }

  @Test
  void theDataExportHasAVitaSectionWithTheAbhaNumberMasked() {
    String amma = addParent("Amma");
    api.put("/api/vita/people/" + amma + "/profile", Map.of("abhaNumber", "12345678909876", "bloodGroup", "b_pos"));
    api.post("/api/vita/people/" + amma + "/allergies", Map.of("substance", "Sulfa"));

    assertThat(api.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    var export = api.get("/api/data/export");
    assertThat(export.ok()).as(export.body()).isTrue();
    JsonNode vita = export.json().path("vita");
    assertThat(vita.path("people").toString()).contains("Amma");
    assertThat(vita.path("records").path("vita_profile").toString()).contains("9876").contains("b_pos");
    assertThat(vita.path("records").path("vita_allergy").toString()).contains("Sulfa");
    assertThat(export.body()).doesNotContain("12345678909876").doesNotContain("abhaNumberEnc");
  }

  private static JsonNode find(JsonNode items, String key) {
    for (JsonNode i : items) if (i.path("key").asText().equals(key)) return i;
    throw new AssertionError("no setting " + key);
  }
}
