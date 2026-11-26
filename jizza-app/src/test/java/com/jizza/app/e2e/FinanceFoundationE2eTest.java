package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Kai Portfolio, Phase 0 through the API: the profile and "Me" on the first visit, people the
 * person manages, consent notes for managed adults, Settings with Kai defaults and overrides, and
 * the finance section of the data export.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class FinanceFoundationE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;

  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "finance-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
  }

  @Test
  void theFirstVisitCreatesTheProfileAndMeOnce() {
    var first = api.get("/api/finance/profile");
    assertThat(first.ok()).as(first.body()).isTrue();
    var again = api.get("/api/finance/profile").json();

    assertThat(again.path("profile").path("id").asText())
        .isEqualTo(first.json().path("profile").path("id").asText());
    assertThat(again.path("people")).hasSize(1);
    assertThat(again.path("people").get(0).path("kind").asText()).isEqualTo("self");
    assertThat(again.path("settings").path("hideAmounts").asBoolean()).isFalse();
    assertThat(api.get("/api/finance/status").json().path("enabled").asBoolean()).isTrue();
  }

  @Test
  void settingsChangeOnlyWhatIsSent() {
    api.get("/api/finance/profile");
    assertThat(api.patch("/api/finance/profile/settings", Map.of("hideAmounts", true)).ok()).isTrue();
    var s = api.patch("/api/finance/profile/settings", Map.of("advanced", true)).json();

    assertThat(s.path("hideAmounts").asBoolean()).isTrue();
    assertThat(s.path("advanced").asBoolean()).isTrue();
  }

  @Test
  void setupGuideMarksAreKeptStepByStep() {
    api.get("/api/finance/profile");
    assertThat(api.patch("/api/finance/profile/settings", Map.of("setupMarks", Map.of("tax", "done"))).ok()).isTrue();
    var s = api.patch("/api/finance/profile/settings",
        Map.of("setupMarks", Map.of("people", "skipped"), "setupGuideHidden", true)).json();
    assertThat(s.path("setupMarks").path("tax").asText()).isEqualTo("done");
    assertThat(s.path("setupMarks").path("people").asText()).isEqualTo("skipped");
    assertThat(s.path("setupGuideHidden").asBoolean()).isTrue();

    var cleared = api.patch("/api/finance/profile/settings", Map.of("setupMarks", Map.of("tax", ""))).json();
    assertThat(cleared.path("setupMarks").has("tax")).isFalse();
    assertThat(cleared.path("setupMarks").path("people").asText()).isEqualTo("skipped");

    assertThat(api.patch("/api/finance/profile/settings", Map.of("setupMarks", Map.of("lottery", "done"))).status())
        .isEqualTo(400);
    assertThat(api.patch("/api/finance/profile/settings", Map.of("setupMarks", Map.of("tax", "maybe"))).status())
        .isEqualTo(400);
  }

  @Test
  void peopleYouManageAreAddedEditedAndArchived() {
    var child = api.post("/api/finance/people", Map.of("displayName", "Riya", "kind", "child",
        "type", "minor", "dob", "2018-06-01", "isDependent", true));
    assertThat(child.ok()).as(child.body()).isTrue();
    String id = child.json().path("id").asText();

    var renamed = api.patch("/api/finance/people/" + id, Map.of("displayName", "Riya S"));
    assertThat(renamed.json().path("displayName").asText()).isEqualTo("Riya S");
    assertThat(renamed.json().path("type").asText()).isEqualTo("minor");

    var bad = api.post("/api/finance/people", Map.of("displayName", "Me too", "kind", "self"));
    assertThat(bad.status()).isEqualTo(400);

    String selfId = api.get("/api/finance/people").json().get(0).path("id").asText();
    assertThat(api.delete("/api/finance/people/" + selfId).status()).isEqualTo(400);

    var archived = api.delete("/api/finance/people/" + id);
    assertThat(archived.json().path("status").asText()).isEqualTo("archived");
    assertThat(api.get("/api/finance/profile").json().path("setupProgress").path("peopleManaged").asInt())
        .isZero();
  }

  @Test
  void consentNotesAreRecordedAndWithdrawnWithStepUp() {
    String mother = api.post("/api/finance/people", Map.of("displayName", "Amma", "kind", "parent",
        "type", "individual", "dob", "1958-02-01")).json().path("id").asText();
    var note = api.post("/api/finance/people/" + mother + "/consents",
        Map.of("scope", "manage", "channel", "in person"));
    assertThat(note.ok()).as(note.body()).isTrue();
    String noteId = note.json().path("id").asText();
    assertThat(api.get("/api/finance/people/" + mother + "/consents").json()).hasSize(1);

    var child = api.post("/api/finance/people", Map.of("displayName", "Riya", "kind", "child",
        "type", "minor", "dob", "2018-06-01")).json().path("id").asText();
    assertThat(api.post("/api/finance/people/" + child + "/consents", Map.of("scope", "view")).status())
        .isEqualTo(400);

    assertThat(api.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    var revoked = api.post("/api/finance/consents/" + noteId + "/revoke", Map.of());
    assertThat(revoked.ok()).as(revoked.body()).isTrue();
    assertThat(revoked.json().path("withdrawnAt").isNull()).isFalse();
    assertThat(revoked.json().path("purgeAfter").asText()).isNotBlank();
    assertThat(api.post("/api/finance/consents/" + noteId + "/revoke", Map.of()).status()).isEqualTo(409);
  }

  @Test
  void settingsShowKaiDefaultsOverridesAndUnverifiedFigures() {
    JsonNode items = api.get("/api/finance/config").json();
    JsonNode tax = find(items, "tax.new_regime");
    assertThat(tax.path("needsVerification").asBoolean()).isTrue();
    assertThat(tax.path("source").asText()).isEqualTo("kai_default");
    assertThat(find(items, "returns.expected").path("needsVerification").asBoolean()).isFalse();

    var mine = api.put("/api/finance/config/returns.expected",
        Map.of("value", Map.of("equity", 9, "debt", 6.5, "gold", 7, "cash", 5.5)));
    assertThat(mine.ok()).as(mine.body()).isTrue();
    assertThat(mine.json().path("source").asText()).isEqualTo("you");
    assertThat(mine.json().path("value").path("equity").asInt()).isEqualTo(9);

    var wrong = api.put("/api/finance/config/returns.expected", Map.of("value", Map.of("equity", "high")));
    assertThat(wrong.status()).isEqualTo(400);
    assertThat(api.put("/api/finance/config/no.such.key", Map.of("value", 1)).status()).isEqualTo(404);

    var reset = api.delete("/api/finance/config/returns.expected");
    assertThat(reset.json().path("source").asText()).isEqualTo("kai_default");
    assertThat(reset.json().path("value").path("equity").asInt()).isEqualTo(10);
  }

  @Test
  void theDataExportHasAFinanceSection() {
    api.post("/api/finance/people", Map.of("displayName", "Riya", "kind", "child",
        "type", "minor", "dob", "2018-06-01"));
    api.put("/api/finance/config/returns.expected",
        Map.of("value", Map.of("equity", 9, "debt", 6.5, "gold", 7, "cash", 5.5)));

    assertThat(api.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    var export = api.get("/api/data/export");
    assertThat(export.ok()).as(export.body()).isTrue();
    JsonNode finance = export.json().path("finance");
    assertThat(finance.path("people")).hasSize(2);
    assertThat(finance.path("settingsOverrides").get(0).path("key").asText()).isEqualTo("returns.expected");
    assertThat(export.body()).doesNotContain("panEnc").doesNotContain("pan_enc");
  }

  private static JsonNode find(JsonNode items, String key) {
    for (JsonNode i : items) if (i.path("key").asText().equals(key)) return i;
    throw new AssertionError("no setting " + key);
  }
}
