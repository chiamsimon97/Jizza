package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.vita.measure.VitaMeasurementUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Fixture I of spec section 10: a reading entered twice, a slip, and a reading moved to the right
 * person.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class FixtureITest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private VitaMeasurementUseCase readings;
  @Autowired private VitaScopeResolver scopes;
  private ApiClient api;
  private String father;
  private String mother;
  private UUID owner;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "fixture-i-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(login.ok()).isTrue();
    owner = UUID.fromString(login.json().path("userId").asText());
    father = api.post("/api/people", Map.of("displayName", "Father", "kind", "parent", "type", "individual"))
        .json().path("id").asText();
    mother = api.post("/api/people", Map.of("displayName", "Mother", "kind", "parent", "type", "individual"))
        .json().path("id").asText();
  }

  private String readings(String person) {
    return "/api/vita/people/" + person + "/measurements";
  }

  @Test
  void theSameReadingFromTheDeviceAMinuteLaterIsAskedAboutAndKeptOnce() {
    var typed = api.post(readings(father), Map.of("kind", "bp", "value", 148, "value2", 92,
        "takenAt", "2026-11-20T07:55"));
    assertThat(typed.ok()).as(typed.body()).isTrue();

    // Typed again at 07:56: "Already saved. Add anyway?"
    var again = api.post(readings(father), Map.of("kind", "bp", "value", 148, "value2", 92,
        "takenAt", "2026-11-20T07:56"));
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.body()).contains("Already saved. Add anyway?");

    // The same values arriving from his device at 07:56 are not stored a second time.
    var fromDevice = readings.create(scopes.person(owner, UUID.fromString(father)),
        Json.obj().put("kind", "bp").put("value", 148).put("value2", 92).put("takenAt", "2026-11-20T07:56"),
        "health_connect");
    assertThat(fromDevice.path("duplicateOf").asText()).isEqualTo(typed.json().path("id").asText());
    assertThat(api.get(readings(father)).json()).hasSize(1);

    // Unless the user says yes.
    var yes = api.post(readings(father) + "?confirmDuplicate=true", Map.of("kind", "bp", "value", 148, "value2", 92,
        "takenAt", "2026-11-20T07:56"));
    assertThat(yes.ok()).as(yes.body()).isTrue();
    assertThat(api.get(readings(father)).json()).hasSize(2);
  }

  @Test
  void aSlipIsRefusedAndRaisesNothing() {
    int before = api.get("/api/notifications").json().size();
    var slip = api.post(readings(father), Map.of("kind", "bp", "value", 1820, "value2", 112));
    assertThat(slip.status()).isEqualTo(400);
    assertThat(api.get(readings(father)).json()).isEmpty();
    assertThat(api.get("/api/notifications").json().size()).isEqualTo(before);
  }

  @Test
  void aReadingSavedUnderMotherMovesToFatherAndKeepsItsTimeAndSource() {
    var saved = api.post(readings(mother), Map.of("kind", "bp", "value", 148, "value2", 92,
        "takenAt", "2026-11-20T07:55"));
    JsonNode row = saved.json();
    var moved = api.post(readings(mother) + "/" + row.path("id").asText() + "/move", Map.of("toPersonId", father));
    assertThat(moved.ok()).as(moved.body()).isTrue();
    assertThat(api.get(readings(mother)).json()).isEmpty();
    JsonNode now = api.get(readings(father)).json().get(0);
    assertThat(now.path("takenAt").asText()).isEqualTo(row.path("takenAt").asText());
    assertThat(now.path("source").asText()).isEqualTo(row.path("source").asText());
    assertThat(now.path("id").asText()).isEqualTo(row.path("id").asText());
  }
}
