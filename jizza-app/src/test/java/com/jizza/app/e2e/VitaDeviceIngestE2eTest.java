package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Health data from phones on the web twin (Vita spec 5.11, Phase 3): what each data type becomes, a
 * revised daily total replaces the old one, a red flag from a device alerts without asking, raw
 * heart rate and oversize batches are refused, and another account can neither see a connection
 * nor send through it. The signed phone routes are covered in {@link PhoneE2eTest}.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaDeviceIngestE2eTest extends E2eSupport {

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  @LocalServerPort private int port;

  private ApiClient signUp() {
    ApiClient c = new ApiClient(port);
    c.prime();
    String email = "vita-devices-" + UUID.randomUUID() + "@jizza.local";
    assertThat(c.post("/api/auth/register", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();
    assertThat(c.post("/api/auth/login", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();
    return c;
  }

  private static JsonNode ok(ApiClient.Result r) {
    assertThat(r.ok()).as(r.body()).isTrue();
    return r.json();
  }

  private static String person(ApiClient api, String name) {
    return ok(api.post("/api/people", Map.of("displayName", name, "kind", "parent", "type", "individual"))).path("id").asText();
  }

  private static String grant(ApiClient api, String person, List<String> types) {
    return ok(api.post("/api/vita/connectors", Map.of("personId", person, "source", "apple_health", "dataTypes", types)))
        .path("id").asText();
  }

  private static JsonNode send(ApiClient api, String person, String grant, List<?> records) {
    return ok(api.post("/api/vita/ingest", Map.of("personId", person, "grantId", grant, "records", records)));
  }

  @Test
  void eachDataTypeBecomesItsReadingAndARevisedTotalReplacesTheOld() {
    ApiClient api = signUp();
    String mother = person(api, "Mother");
    String g = grant(api, mother, List.of("steps", "sleep", "workout", "resting_heart_rate", "weight", "glucose"));
    String day = LocalDate.now(INDIA).minusDays(1).toString();
    Instant woke = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MINUTES);
    JsonNode out = send(api, mother, g, List.of(
        Map.of("externalId", "s1", "type", "steps", "date", day, "value", 4100),
        Map.of("externalId", "z1", "type", "sleep", "startedAt", woke.minus(Duration.ofMinutes(405)).toString(),
            "endedAt", woke.toString()),
        Map.of("externalId", "w1", "type", "workout", "workoutType", "yoga",
            "startedAt", woke.plus(Duration.ofMinutes(10)).toString(), "endedAt", woke.plus(Duration.ofMinutes(50)).toString()),
        Map.of("externalId", "r1", "type", "resting_heart_rate", "value", 64.4, "takenAt", woke.toString()),
        Map.of("externalId", "kg1", "type", "weight", "value", 61.237, "takenAt", woke.toString()),
        Map.of("externalId", "g1", "type", "glucose", "value", 104.04, "context", "fasting", "takenAt", woke.toString()),
        Map.of("externalId", "h1", "type", "heart_rate", "value", 88, "takenAt", woke.toString())));
    assertThat(out.path("added").asInt()).isEqualTo(6);
    assertThat(out.path("refused").asInt()).isEqualTo(1);
    assertThat(out.path("results").get(6).path("reason").asText()).contains("resting");

    String m = "/api/vita/people/" + mother + "/measurements?kind=";
    JsonNode steps = ok(api.get(m + "steps")).get(0);
    assertThat(steps.path("value").decimalValue()).isEqualByComparingTo("4100");
    assertThat(steps.path("source").asText()).isEqualTo("apple_health");
    assertThat(steps.path("detail").path("date").asText()).isEqualTo(day);
    JsonNode sleep = ok(api.get(m + "sleep")).get(0);
    assertThat(sleep.path("value").decimalValue()).isEqualByComparingTo("6.75");
    assertThat(sleep.path("startedAt").isNull()).isFalse();
    assertThat(sleep.path("endedAt").isNull()).isFalse();
    JsonNode walk = ok(api.get(m + "workout")).get(0);
    assertThat(walk.path("value").decimalValue()).isEqualByComparingTo("40");
    assertThat(walk.path("detail").path("workoutType").asText()).isEqualTo("yoga");
    assertThat(ok(api.get(m + "resting_heart_rate")).get(0).path("value").decimalValue()).isEqualByComparingTo("64");
    assertThat(ok(api.get(m + "weight")).get(0).path("value").decimalValue()).isEqualByComparingTo("61.24");
    JsonNode sugar = ok(api.get(m + "glucose")).get(0);
    assertThat(sugar.path("value").decimalValue()).isEqualByComparingTo("104.0");
    assertThat(sugar.path("context").asText()).isEqualTo("fasting");
    assertThat(sugar.path("externalId").asText()).isEqualTo("g1");

    // The phone revises the day's total: the same id replaces the value, no new row.
    JsonNode revised = send(api, mother, g, List.of(Map.of("externalId", "s1", "type", "steps", "date", day, "value", 6020)));
    assertThat(revised.path("updated").asInt()).isEqualTo(1);
    JsonNode allSteps = ok(api.get(m + "steps"));
    assertThat(allSteps).hasSize(1);
    assertThat(allSteps.get(0).path("value").decimalValue()).isEqualByComparingTo("6020");

    // A record the person removed stays removed when the phone sends it again.
    String sleepId = sleep.path("id").asText();
    assertThat(api.delete("/api/vita/people/" + mother + "/measurements/" + sleepId).status()).isBetween(200, 204);
    JsonNode resent = send(api, mother, g, List.of(Map.of("externalId", "z1", "type", "sleep",
        "startedAt", woke.minus(Duration.ofMinutes(405)).toString(), "endedAt", woke.toString())));
    assertThat(resent.path("unchanged").asInt()).isEqualTo(1);
    assertThat(ok(api.get(m + "sleep"))).isEmpty();
  }

  @Test
  void aRedFlagFromADeviceAlertsWithoutAskingAndBadRecordsAreRefusedOneByOne() {
    ApiClient api = signUp();
    String father = person(api, "Father");
    String g = grant(api, father, List.of("bp"));
    int before = api.get("/api/notifications").json().size();
    Instant at = Instant.now().minus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.SECONDS);
    JsonNode out = send(api, father, g, List.of(
        Map.of("externalId", "bp1", "type", "bp", "value", 182, "value2", 112, "takenAt", at.toString()),
        Map.of("externalId", "bp2", "type", "bp", "value", 1820, "value2", 82, "takenAt", at.plusSeconds(600).toString()),
        Map.of("externalId", "bp3", "type", "bp", "value", 130, "takenAt", at.toString()),
        Map.of("externalId", "bp4", "type", "bp", "value", 130, "value2", 80, "takenAt", "2099-01-01T08:00:00Z"),
        Map.of("type", "bp", "value", 130, "value2", 80, "takenAt", at.toString())));
    assertThat(out.path("added").asInt()).isEqualTo(1);
    assertThat(out.path("refused").asInt()).isEqualTo(4);
    JsonNode first = out.path("results").get(0);
    assertThat(first.path("alerted").asBoolean()).isTrue();
    JsonNode row = ok(api.get("/api/vita/people/" + father + "/measurements/" + first.path("id").asText()));
    assertThat(row.path("confirmNeeded").asBoolean()).isFalse();
    JsonNode notes = api.get("/api/notifications").json();
    assertThat(notes.size()).isEqualTo(before + 1);
    assertThat(notes.toString()).doesNotContain("182");

    // A batch over the limit is refused whole.
    List<Map<String, Object>> big = new ArrayList<>();
    for (int i = 0; i < 501; i++) {
      big.add(Map.of("externalId", "x" + i, "type", "bp", "value", 120, "value2", 80, "takenAt", at.toString()));
    }
    assertThat(api.post("/api/vita/ingest", Map.of("personId", father, "grantId", g, "records", big)).status()).isEqualTo(400);
    // A grant needs known types, and raw heart rate is not one.
    assertThat(api.post("/api/vita/connectors", Map.of("personId", father, "source", "apple_health",
        "dataTypes", List.of("heart_rate"))).status()).isEqualTo(400);
    assertThat(api.post("/api/vita/connectors", Map.of("personId", father, "source", "fitbit",
        "dataTypes", List.of("steps"))).status()).isEqualTo(400);
  }

  @Test
  void connectingAgainChangesTheTypesAndAnotherAccountCanNeitherSeeNorSend() {
    ApiClient api = signUp();
    String father = person(api, "Father");
    String g = grant(api, father, List.of("steps"));
    // The same person and source from the web is one connection; its types are replaced.
    assertThat(grant(api, father, List.of("steps", "sleep"))).isEqualTo(g);
    JsonNode list = ok(api.get("/api/vita/connectors?personId=" + father));
    assertThat(list).hasSize(1);
    assertThat(list.get(0).path("dataTypes")).hasSize(2);
    assertThat(ok(api.get("/api/vita/connectors"))).hasSize(1);

    // Another account sees nothing and cannot send.
    ApiClient other = signUp();
    assertThat(other.get("/api/vita/connectors?personId=" + father).status()).isEqualTo(404);
    assertThat(ok(other.get("/api/vita/connectors"))).isEmpty();
    assertThat(other.post("/api/vita/ingest", Map.of("personId", father, "grantId", g, "records", List.of())).status())
        .isEqualTo(404);
    assertThat(other.delete("/api/vita/connectors/" + g + "?personId=" + father).status()).isEqualTo(404);
  }
}
