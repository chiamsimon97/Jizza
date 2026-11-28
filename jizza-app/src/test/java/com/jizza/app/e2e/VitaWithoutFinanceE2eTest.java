package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Vita with Kai's switch off (spec 4.1, settled decision 2): the shared people list and the Health
 * API work, Kai's API does not exist, and no finance profile is ever created along the way.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaWithoutFinanceE2eTest extends E2eSupport {

  @DynamicPropertySource
  static void financeOff(DynamicPropertyRegistry registry) {
    registry.add("e2e.finance-enabled", () -> "false");
    registry.add("e2e.health-enabled", () -> "true");
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void peopleAndTheHealthRecordWorkWithFinanceOffAndMakeNoFinanceProfile() {
    ApiClient api = new ApiClient(port);
    api.prime();
    String email = "vita-only-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();

    assertThat(api.get("/api/finance/status").status()).isEqualTo(404);
    assertThat(api.get("/api/finance/people").status()).isEqualTo(404);
    assertThat(api.get("/api/vita/status").json().path("enabled").asBoolean()).isTrue();

    var people = api.get("/api/people");
    assertThat(people.ok()).as(people.body()).isTrue();
    assertThat(people.json()).hasSize(1);
    String me = people.json().get(0).path("id").asText();
    var mother = api.post("/api/people", Map.of("displayName", "Amma", "kind", "parent", "type", "individual"));
    assertThat(mother.ok()).as(mother.body()).isTrue();

    assertThat(api.put("/api/vita/people/" + me + "/profile", Map.of("bloodGroup", "o_pos")).ok()).isTrue();
    assertThat(api.post("/api/vita/quick-start", Map.of("step", "blood_group", "answers", Map.of("bloodGroup", "o_pos")))
        .ok()).isTrue();
    assertThat(api.post("/api/vita/people/" + mother.json().path("id").asText() + "/measurements",
        Map.of("kind", "weight", "value", 58)).ok()).isTrue();
    assertThat(api.get("/api/vita/config").ok()).isTrue();

    UUID userId = UUID.fromString(jdbc.queryForObject(
        "select id::text from users where email = ?", String.class, email));
    assertThat(jdbc.queryForObject("select count(*) from fin_profile where user_id = ?", Integer.class, userId))
        .as("finance profiles made for a Vita-only user").isZero();
    assertThat(jdbc.queryForObject("select count(*) from fin_person where user_id = ?", Integer.class, userId))
        .isEqualTo(2);
  }

  @Test
  void healthCoverAndClaimsWorkWithFinanceOff() {
    ApiClient api = new ApiClient(port);
    api.prime();
    String email = "vita-only-ins-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();
    String me = api.get("/api/people").json().get(0).path("id").asText();
    var policy = api.post("/api/vita/policies", Map.of("kind", "health", "sumAssured", 500000, "renewalDate", "2027-03-31"));
    assertThat(policy.ok()).as(policy.body()).isTrue();
    String id = policy.json().path("id").asText();
    assertThat(policy.json().path("coveredPersonIds").get(0).asText()).isEqualTo(me);
    var claim = api.post("/api/vita/people/" + me + "/claims", Map.of("policyId", id, "admittedOn", "2026-06-10",
        "amountClaimed", 20000, "amountPaid", 18000, "status", "settled"));
    assertThat(claim.ok()).as(claim.body()).isTrue();
    assertThat(api.get("/api/vita/policies/" + id).json().path("leftThisYear").decimalValue()).isEqualByComparingTo("482000");
    assertThat(api.get("/api/finance/protection").status()).isEqualTo(404);
    assertThat(api.get("/api/finance/policies").status()).isEqualTo(404);
  }
}
