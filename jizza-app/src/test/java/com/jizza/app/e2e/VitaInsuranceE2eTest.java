package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Vita's insurance and claims with both switches on (Vita spec 4.3, Phase 2b): Fixture F exactly
 * through the API, Kai's Protection view reading the floater as one policy with what was used,
 * and Kai's forms leaving health cover to Vita. VitaInsuranceHealthOffE2eTest covers health off
 * and VitaWithoutFinanceE2eTest finance off.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaInsuranceE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private com.jizza.platform.usecase.finance.review.AlertService alerts;

  private ApiClient signUp(String who) {
    ApiClient c = new ApiClient(port);
    c.prime();
    String email = "vita-ins-" + who + "-" + UUID.randomUUID() + "@jizza.local";
    assertThat(c.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(c.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    return c;
  }

  private static JsonNode ok(ApiClient.Result r) {
    assertThat(r.ok()).as(r.body()).isTrue();
    return r.json();
  }

  private record Household(ApiClient api, String me, String mother, String father) {}

  private Household household() {
    ApiClient api = signUp("you");
    String me = ok(api.get("/api/people")).get(0).path("id").asText();
    String mother = ok(api.post("/api/people", Map.of("displayName", "Mother", "kind", "parent", "type", "individual"))).path("id").asText();
    String father = ok(api.post("/api/people", Map.of("displayName", "Father", "kind", "parent", "type", "individual"))).path("id").asText();
    return new Household(api, me, mother, father);
  }

  private static Map<String, Object> floater(Household h) {
    Map<String, Object> body = new HashMap<>();
    body.put("kind", "health");
    body.put("providerName", "Sample Health Insurer");
    body.put("sumAssured", 1000000);
    body.put("premium", 32000);
    body.put("premiumFrequency", "yearly");
    body.put("renewalDate", "2027-03-31");
    body.put("coveredPersonIds", List.of(h.me(), h.mother(), h.father()));
    return body;
  }

  @Test
  void fixtureFAFamilyFloaterAndAClaim() {
    Household h = household();
    ApiClient api = h.api();
    JsonNode policy = ok(api.post("/api/vita/policies", floater(h)));
    String id = policy.path("id").asText();
    assertThat(policy.path("floater").asBoolean()).isTrue();
    assertThat(policy.path("coveredPersonIds")).hasSize(3);
    assertThat(policy.path("policyYear").path("from").asText()).isEqualTo("2026-04-01");
    assertThat(policy.path("policyYear").path("to").asText()).isEqualTo("2027-03-31");
    assertThat(policy.path("renewalReminderOn").asText()).isEqualTo("2027-03-01");
    assertThat(policy.path("usedThisYear").decimalValue()).isEqualByComparingTo("0");

    // The renewal reminder: one, for the holder, 30 days before, at 9 in the morning in India.
    List<JsonNode> renewals = new java.util.ArrayList<>();
    ok(api.get("/api/vita/people/" + h.me() + "/reminders")).forEach(r -> {
      if ("policy_renewal".equals(r.path("kind").asText())) renewals.add(r);
    });
    assertThat(renewals).hasSize(1);
    assertThat(java.time.OffsetDateTime.parse(renewals.get(0).path("dueAt").asText()).toInstant())
        .isEqualTo(java.time.Instant.parse("2027-03-01T03:30:00Z"));

    // Father's stay, 2-4 Aug 2026: claimed 1,20,000, paid 1,05,000, settled.
    JsonNode claim = ok(api.post("/api/vita/people/" + h.father() + "/claims", Map.of("policyId", id,
        "admittedOn", "2026-08-02", "dischargedOn", "2026-08-04", "amountClaimed", 120000,
        "amountPaid", 105000, "status", "settled")));
    assertThat(claim.path("outOfPocket").decimalValue()).isEqualByComparingTo("15000");
    assertThat(claim.path("policyYearTo").asText()).isEqualTo("2027-03-31");

    JsonNode after = ok(api.get("/api/vita/policies/" + id));
    assertThat(after.path("usedThisYear").decimalValue()).isEqualByComparingTo("105000");
    assertThat(after.path("leftThisYear").decimalValue()).isEqualByComparingTo("895000");
    assertThat(after.path("claimCount").asInt()).isEqualTo(1);

    // A stay on 2 Apr 2027 is in the next policy year and changes none of the figures.
    JsonNode next = ok(api.post("/api/vita/people/" + h.mother() + "/claims", Map.of("policyId", id,
        "admittedOn", "2027-04-02", "amountClaimed", 50000, "amountPaid", 40000, "status", "settled")));
    assertThat(next.path("policyYearTo").asText()).isEqualTo("2028-03-31");
    after = ok(api.get("/api/vita/policies/" + id));
    assertThat(after.path("usedThisYear").decimalValue()).isEqualByComparingTo("105000");
    assertThat(after.path("leftThisYear").decimalValue()).isEqualByComparingTo("895000");

    // Kai's Protection view: one floater policy of 10,00,000 covering three people, 1,05,000 used.
    JsonNode protection = ok(api.get("/api/finance/protection"));
    assertThat(protection.path("healthInVita").asBoolean()).isTrue();
    List<JsonNode> health = new java.util.ArrayList<>();
    protection.path("policies").forEach(p -> {
      if ("health".equals(p.path("kind").asText())) health.add(p);
    });
    assertThat(health).hasSize(1);
    assertThat(health.get(0).path("sumAssured").decimalValue()).isEqualByComparingTo("1000000");
    assertThat(health.get(0).path("coveredPersonIds")).hasSize(3);
    assertThat(health.get(0).path("floater").asBoolean()).isTrue();
    assertThat(health.get(0).path("usedThisYear").decimalValue()).isEqualByComparingTo("105000");
    JsonNode kaiRow = ok(api.get("/api/finance/policies")).get(0);
    assertThat(kaiRow.path("editInVita").asBoolean()).isTrue();
    assertThat(kaiRow.path("usedThisYear").decimalValue()).isEqualByComparingTo("105000");
  }

  @Test
  void kaiLeavesHealthCoverToVitaWhileHealthIsOn() {
    Household h = household();
    ApiClient api = h.api();
    String id = ok(api.post("/api/vita/policies", floater(h))).path("id").asText();
    var edit = api.patch("/api/finance/policies/" + id, Map.of("sumAssured", 500000));
    assertThat(edit.status()).isEqualTo(400);
    assertThat(edit.body()).contains("kept in Vita");
    assertThat(api.delete("/api/finance/policies/" + id).status()).isEqualTo(400);
    assertThat(api.post("/api/finance/policies", Map.of("kind", "super_topup", "sumAssured", 500000)).status()).isEqualTo(400);
    assertThat(api.post("/api/finance/policies", Map.of("kind", "government_scheme", "sumAssured", 500000)).status()).isEqualTo(400);
    // Term cover stays Kai's, and Kai cannot turn it into health cover either.
    String term = ok(api.post("/api/finance/policies", Map.of("kind", "term", "sumAssured", 5000000))).path("id").asText();
    assertThat(api.patch("/api/finance/policies/" + term, Map.of("kind", "health")).status()).isEqualTo(400);
    assertThat(ok(api.patch("/api/finance/policies/" + term, Map.of("sumAssured", 6000000))).path("sumAssured").decimalValue())
        .isEqualByComparingTo("6000000");
    // Vita refuses Kai's kinds and does not reach Kai's term policy.
    Map<String, Object> termBody = new HashMap<>(floater(h));
    termBody.put("kind", "term");
    assertThat(api.post("/api/vita/policies", termBody).status()).isEqualTo(400);
    assertThat(api.get("/api/vita/policies/" + term).status()).isEqualTo(404);
    assertThat(ok(api.get("/api/vita/policies"))).hasSize(1);
  }

  @Test
  void numbersAreStoredEncryptedAndShownMasked() {
    Household h = household();
    ApiClient api = h.api();
    String id = ok(api.post("/api/vita/policies", floater(h))).path("id").asText();
    JsonNode detail = ok(api.put("/api/vita/policies/" + id + "/detail", Map.of("policyNumber", "HLT/2026/00123456",
        "cardNumber", "CARD 9876 5432", "tpaName", "Sample TPA", "copayPct", 10,
        "waitingPeriods", List.of("Pre-existing conditions: as printed on the policy"))));
    assertThat(detail.path("policyNumberMasked").asText()).isEqualTo("•••• 3456");
    assertThat(detail.path("cardNumberMasked").asText()).isEqualTo("•••• 5432");
    assertThat(detail.path("waitingPeriods")).hasSize(1);
    for (String path : List.of("/api/vita/policies/" + id + "/detail", "/api/vita/policies", "/api/vita/policies/" + id)) {
      String body = ok(api.get(path)).toString();
      assertThat(body).doesNotContain("00123456").doesNotContain("9876 5432");
    }
    String stored = jdbc.queryForObject("select encode(policy_number_enc, 'escape') from vita_policy_detail where policy_id = ?::uuid",
        String.class, id);
    assertThat(stored).doesNotContain("00123456");
    assertThat(api.put("/api/vita/policies/" + id + "/detail", Map.of("policyNumber", "<script>")).status()).isEqualTo(400);

    // The person's export carries the cover masked; deleting the policy takes its detail and claims.
    ok(api.post("/api/vita/people/" + h.mother() + "/claims", Map.of("policyId", id, "admittedOn", "2026-09-01",
        "amountClaimed", 1000, "status", "filed")));
    assertThat(api.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    String export = ok(api.get("/api/vita/people/" + h.mother() + "/export")).toString();
    assertThat(export).contains("•••• 3456").doesNotContain("00123456").contains("vita_claim");
    assertThat(api.delete("/api/vita/policies/" + id).status()).isEqualTo(204);
    assertThat(jdbc.queryForObject("select count(*) from vita_claim where policy_id = ?::uuid", Integer.class, id)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from vita_policy_detail where policy_id = ?::uuid", Integer.class, id)).isZero();
  }

  @Test
  void claimsAreCheckedAndAnotherAccountReachesNothing() {
    Household h = household();
    ApiClient api = h.api();
    String id = ok(api.post("/api/vita/policies", floater(h))).path("id").asText();
    String claims = "/api/vita/people/" + h.father() + "/claims";
    assertThat(api.post(claims, Map.of("policyId", id, "admittedOn", "2026-08-02", "status", "settled",
        "amountClaimed", 1000)).status()).as("settled without the amount paid").isEqualTo(400);
    assertThat(api.post(claims, Map.of("policyId", id, "admittedOn", "2026-08-04", "dischargedOn", "2026-08-02"))
        .status()).isEqualTo(400);
    assertThat(api.post(claims, Map.of("policyId", id, "admittedOn", "2026-08-02", "amountClaimed", -1)).status())
        .isEqualTo(400);
    // A policy that does not cover the person takes no claim for them.
    String solo = ok(api.post("/api/vita/policies", Map.of("kind", "health", "sumAssured", 300000,
        "coveredPersonIds", List.of(h.mother())))).path("id").asText();
    assertThat(api.post(claims, Map.of("policyId", solo, "admittedOn", "2026-08-02")).status()).isEqualTo(404);
    String claim = ok(api.post(claims, Map.of("policyId", id, "admittedOn", "2026-08-02", "amountClaimed", 1000)))
        .path("id").asText();
    assertThat(ok(api.put(claims + "/" + claim, Map.of("status", "rejected"))).path("outOfPocket").decimalValue())
        .isEqualByComparingTo("1000");
    // A government scheme may have no sum printed; a private policy needs one.
    assertThat(api.post("/api/vita/policies", Map.of("kind", "government_scheme", "providerName", "CGHS")).ok()).isTrue();
    assertThat(api.post("/api/vita/policies", Map.of("kind", "health")).status()).isEqualTo(400);

    ApiClient other = signUp("other");
    String otherMe = ok(other.get("/api/people")).get(0).path("id").asText();
    for (String path : List.of("/api/vita/policies/" + id, "/api/vita/policies/" + id + "/detail", claims,
        "/api/vita/policies?personId=" + h.father())) {
      assertThat(other.get(path).status()).as(path).isEqualTo(404);
    }
    assertThat(other.put("/api/vita/policies/" + id, Map.of("sumAssured", 1)).status()).isEqualTo(404);
    assertThat(other.delete("/api/vita/policies/" + id).status()).isEqualTo(404);
    assertThat(other.post(claims, Map.of("policyId", id, "admittedOn", "2026-08-02")).status()).isEqualTo(404);
    assertThat(other.post("/api/vita/people/" + otherMe + "/claims", Map.of("policyId", id, "admittedOn", "2026-08-02"))
        .status()).isEqualTo(404);
    assertThat(other.post("/api/vita/policies", Map.of("kind", "health", "sumAssured", 1,
        "coveredPersonIds", List.of(h.father()))).status()).isEqualTo(404);
    assertThat(ok(other.get("/api/vita/policies"))).isEmpty();
  }

  @Test
  void quickStartAgainKeepsVitaClaimsAndKaiLeavesTheRenewalToVita() {
    ApiClient api = new ApiClient(port);
    api.prime();
    String email = "vita-ins-qs-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    UUID owner = UUID.fromString(login.json().path("userId").asText());
    String me = ok(api.get("/api/people")).get(0).path("id").asText();
    String renews = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(10).toString();

    ok(api.post("/api/finance/quick-start", Map.of("step", "insurance", "answers", Map.of("policies", List.of(
        Map.of("kind", "health", "sumAssured", 500000, "premium", 12000, "premiumFrequency", "yearly",
            "renewalDate", renews))))));
    String id = jdbc.queryForObject("select id::text from fin_insurance_policy where user_id = ? and kind = 'health'",
        String.class, owner);
    ok(api.post("/api/vita/people/" + me + "/claims", Map.of("policyId", id, "admittedOn", "2026-08-02",
        "amountClaimed", 40000, "status", "filed")));

    // Answering the step again changes the same policy: the claim stays on it.
    ok(api.post("/api/finance/quick-start", Map.of("step", "insurance", "answers", Map.of("policies", List.of(
        Map.of("kind", "health", "sumAssured", 500000, "premium", 13500, "premiumFrequency", "yearly",
            "renewalDate", renews))))));
    assertThat(jdbc.queryForList("select id::text from fin_insurance_policy where user_id = ? and kind = 'health'",
        String.class, owner)).containsExactly(id);
    assertThat(ok(api.get("/api/vita/policies/" + id)).path("premium").decimalValue()).isEqualByComparingTo("13500");
    assertThat(ok(api.get("/api/vita/people/" + me + "/claims"))).hasSize(1);

    // Left out of the answers, a policy with claims stays; the claim is never lost.
    ok(api.post("/api/finance/quick-start", Map.of("step", "insurance", "answers", Map.of("policies", List.of()))));
    assertThat(jdbc.queryForObject("select count(*) from vita_claim where policy_id = ?::uuid", Integer.class, id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from fin_insurance_policy where id = ?::uuid", Integer.class, id)).isEqualTo(1);

    // Vita keeps the renewal reminder for the health policy, so Kai raises no second one.
    ok(api.put("/api/vita/policies/" + id, Map.of("renewalDate", renews)));
    assertThat(jdbc.queryForObject("select count(*) from vita_reminder where ref_id = ?::uuid and kind = 'policy_renewal'",
        Integer.class, id)).isEqualTo(1);
    alerts.sendAlerts(owner);
    assertThat(api.get("/api/notifications").json().toString()).doesNotContain("policy renews on");
  }
}
