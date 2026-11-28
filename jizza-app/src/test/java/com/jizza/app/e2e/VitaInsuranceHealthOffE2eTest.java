package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Finance on and health off (Vita spec 4.3): Kai behaves exactly as before Vita, adding, changing
 * and removing health cover itself, and none of Vita's insurance routes exist.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaInsuranceHealthOffE2eTest extends E2eSupport {

  @DynamicPropertySource
  static void healthOff(DynamicPropertyRegistry registry) {
    registry.add("e2e.finance-enabled", () -> "true");
    registry.add("e2e.health-enabled", () -> "false");
  }

  @LocalServerPort private int port;

  @Test
  void kaiStillEditsHealthCoverAndVitasRoutesAreAbsent() {
    ApiClient api = new ApiClient(port);
    api.prime();
    String email = "kai-ins-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();

    var me = api.get("/api/people");
    assertThat(me.ok()).as(me.body()).isTrue();
    String self = me.json().get(0).path("id").asText();
    var mother = api.post("/api/people", Map.of("displayName", "Mother", "kind", "parent", "type", "individual"));
    assertThat(mother.ok()).as(mother.body()).isTrue();
    var added = api.post("/api/finance/policies", Map.of("kind", "health", "sumAssured", 1000000,
        "renewalDate", "2027-03-31", "coveredPersonIds", List.of(self, mother.json().path("id").asText())));
    assertThat(added.ok()).as(added.body()).isTrue();
    String id = added.json().path("id").asText();
    var changed = api.patch("/api/finance/policies/" + id, Map.of("sumAssured", 1500000));
    assertThat(changed.ok()).as(changed.body()).isTrue();

    JsonNode row = api.get("/api/finance/policies").json().get(0);
    assertThat(row.path("editInVita").asBoolean()).isFalse();
    assertThat(row.path("usedThisYear").decimalValue()).isEqualByComparingTo("0");
    JsonNode protection = api.get("/api/finance/protection").json();
    assertThat(protection.path("healthInVita").asBoolean()).isFalse();
    assertThat(protection.path("policies")).hasSize(1);
    assertThat(protection.path("policies").get(0).path("floater").asBoolean()).isTrue();

    for (String path : List.of("/api/vita/policies", "/api/vita/policies/" + id, "/api/vita/policies/" + id + "/detail",
        "/api/vita/people/" + self + "/claims")) {
      assertThat(api.get(path).status()).as(path).isEqualTo(404);
    }
    assertThat(api.delete("/api/finance/policies/" + id).status()).isEqualTo(204);
  }
}
