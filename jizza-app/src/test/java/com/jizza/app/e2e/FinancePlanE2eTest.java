package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Kai Portfolio, Phase 1b through the API: the goal library and goals with funding, Quick start
 * steps 7 and 8, the plan builder on Fixture E's inputs (preview and save), the risk profile and
 * the tax regime comparison with its verification flag.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class FinancePlanE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;

  private ApiClient api;
  private String me;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "plan-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    me = api.get("/api/finance/profile").json().path("people").get(0).path("id").asText();
  }

  private JsonNode ok(ApiClient.Result r) {
    assertThat(r.ok()).as(r.body()).isTrue();
    return r.json();
  }

  @Test
  void theLibraryPrefillsAGoalAndItsFundingIsWorkedOut() {
    ok(api.post("/api/finance/quick-start", Map.of("step", "people", "answers", Map.of("me", Map.of("dob", "1992-06-01")))));
    ok(api.post("/api/finance/quick-start", Map.of("step", "spending",
        "answers", Map.of("essentialMonthly", 40000, "otherMonthly", 30000))));

    JsonNode library = ok(api.get("/api/finance/goal-library"));
    Map<String, JsonNode> cards = new HashMap<>();
    library.forEach(c -> cards.put(c.path("type").asText(), c));
    assertThat(cards).containsKeys("emergency", "retirement", "education", "home", "car", "travel");
    assertThat(cards.get("emergency").path("targetAmountToday").decimalValue()).isEqualByComparingTo("240000");
    assertThat(cards.get("retirement").path("targetAmountToday").decimalValue()).isEqualByComparingTo("16800000");
    assertThat(cards.get("retirement").path("targetDate").asText()).isEqualTo("2052-06-01");
    assertThat(cards.get("home").path("targetAmountToday").isNull()).isTrue();

    String target = LocalDate.now().plusYears(10).withDayOfMonth(1).toString();
    JsonNode goal = ok(api.post("/api/finance/goals", Map.of("type", "home", "targetAmountToday", 2000000,
        "targetDate", target, "monthlyContribution", 5000)));
    assertThat(goal.path("goal").path("name").asText()).isEqualTo("Home down payment");
    assertThat(goal.path("goal").path("priorityTier").asText()).isEqualTo("important");
    assertThat(goal.path("funding").path("bucket").asText()).isEqualTo("long");
    assertThat(goal.path("funding").path("requiredSip").decimalValue()).isPositive();
    assertThat(goal.path("risk").asText()).isEqualTo("moderate");
    assertThat(goal.path("riskSource").asText()).isEqualTo("default");

    // An Important goal makes the Goal funding check known.
    JsonNode health = ok(api.get("/api/finance/health"));
    health.path("checks").forEach(c -> {
      if ("goalFunding".equals(c.path("key").asText())) assertThat(c.path("status").asText()).isNotEqualTo("unknown");
    });

    String id = goal.path("goal").path("id").asText();
    assertThat(ok(api.patch("/api/finance/goals/" + id, Map.of("currentValue", 100000)))
        .path("goal").path("currentValue").decimalValue()).isEqualByComparingTo("100000");
    assertThat(ok(api.post("/api/finance/goals/" + id + "/achieve", Map.of())).path("goal").path("status").asText())
        .isEqualTo("achieved");

    var past = api.post("/api/finance/goals", Map.of("type", "travel", "targetAmountToday", 1000, "targetDate", "2020-01-01"));
    assertThat(past.status()).isEqualTo(400);
    ok(api.post("/api/finance/goals", Map.of("type", "emergency")));
    assertThat(api.post("/api/finance/goals", Map.of("type", "emergency")).status()).isEqualTo(409);
  }

  @Test
  void quickStartStepsSevenAndEightSaveGoalsAndTheRiskProfile() {
    JsonNode p = ok(api.post("/api/finance/quick-start", Map.of("step", "goals", "answers", Map.of("goals", List.of(
        Map.of("type", "travel", "targetAmountToday", 150000),
        Map.of("type", "car", "targetAmountToday", 600000))))));
    assertThat(p.path("progress").path("total").asInt()).isEqualTo(8);
    assertThat(ok(api.get("/api/finance/goals"))).hasSize(2);
    // Answering again replaces Quick start's goals.
    ok(api.post("/api/finance/quick-start", Map.of("step", "goals", "answers", Map.of("goals", List.of(
        Map.of("type", "travel", "targetAmountToday", 150000))))));
    assertThat(ok(api.get("/api/finance/goals"))).hasSize(1);
    var tooMany = api.post("/api/finance/quick-start", Map.of("step", "goals", "answers", Map.of("goals", List.of(
        Map.of("type", "travel"), Map.of("type", "car"), Map.of("type", "home"), Map.of("type", "custom")))));
    assertThat(tooMany.status()).isEqualTo(400);

    ok(api.post("/api/finance/quick-start", Map.of("step", "risk", "answers", Map.of("answers", List.of(3, 3, 3, 3, 3, 3)))));
    JsonNode risk = ok(api.get("/api/finance/risk/" + me));
    assertThat(risk.path("questions")).hasSize(6);
    assertThat(risk.path("latest").path("toleranceProfile").asText()).isEqualTo("aggressive");
    assertThat(risk.path("latest").path("profile").asText()).isIn("conservative", "moderate", "aggressive");
    assertThat(risk.path("note").asText()).contains("Indicative only");
    assertThat(api.post("/api/finance/risk/" + me, Map.of("answers", List.of(1, 2))).status()).isEqualTo(400);
  }

  @Test
  void thePlanBuilderSplitsFixtureEAndSavesTheLines() {
    Map<String, Object> body = Map.of("monthly", 50000, "lumpsum", 300000, "age", 34, "riskProfile", "moderate");
    JsonNode view = ok(api.post("/api/finance/builder/preview", body));
    Map<String, String> lines = new HashMap<>();
    view.path("result").path("lines").forEach(l -> lines.put(l.path("key").asText(),
        l.path("monthly").decimalValue().stripTrailingZeros().toPlainString()));
    assertThat(lines).containsEntry("long.equity.large_cap_index", "8700")
        .containsEntry("long.equity.flexi_cap", "6100").containsEntry("short.cash.liquid", "7000")
        .containsEntry("mid.debt.short_duration", "6000");
    assertThat(view.path("result").path("timeline").path("parked").decimalValue()).isEqualByComparingTo("105000");
    assertThat(view.path("savedAt").isNull()).isTrue();

    var edited = new HashMap<String, Object>(body);
    edited.put("edits", List.of(Map.of("key", "long.equity.mid_cap", "monthly", 3000)));
    JsonNode saved = ok(api.post("/api/finance/builder/save", edited));
    assertThat(saved.path("savedAt").isNull()).isFalse();
    assertThat(saved.path("savedLines")).hasSize(11);
    JsonNode again = ok(api.get("/api/finance/builder"));
    assertThat(again.path("input").path("edits")).hasSize(1);
    again.path("result").path("lines").forEach(l -> {
      if ("long.equity.large_cap_index".equals(l.path("key").asText())) {
        assertThat(l.path("monthly").decimalValue()).isEqualByComparingTo("8100");
      }
    });
    assertThat(api.post("/api/finance/builder/preview", Map.of("monthly", 1000,
        "edits", List.of(Map.of("key", "long", "monthly", 5000)))).status()).isEqualTo(400);
  }

  @Test
  void eachGoalGetsAVerdictAndTheRunIsKept() {
    ok(api.post("/api/finance/quick-start", Map.of("step", "people", "answers", Map.of("me", Map.of("dob", "1992-06-01")))));
    String target = LocalDate.now().plusYears(10).withDayOfMonth(1).toString();
    String funded = ok(api.post("/api/finance/goals", Map.of("type", "home", "name", "House", "targetAmountToday", 1000000,
        "targetDate", target, "monthlyContribution", 30000))).path("goal").path("id").asText();
    String bare = ok(api.post("/api/finance/goals", Map.of("type", "travel", "name", "Car", "targetAmountToday", 3000000,
        "targetDate", target))).path("goal").path("id").asText();

    JsonNode checks = ok(api.get("/api/finance/goal-checks"));
    assertThat(checks).hasSize(2);
    Map<String, JsonNode> byName = new HashMap<>();
    checks.forEach(c -> byName.put(c.path("name").asText(), c));
    // Off track first.
    assertThat(checks.get(0).path("goalId").asText()).isEqualTo(bare);
    assertThat(byName.get("Car").path("verdict").asText()).isEqualTo("off_track");
    // Each priority has its own bar from the config: aspirational goals need 50%.
    assertThat(byName.get("Car").path("requiredPct").asDouble()).isEqualTo(50);
    JsonNode house = byName.get("House");
    assertThat(house.path("goalId").asText()).isEqualTo(funded);
    assertThat(house.path("verdict").asText()).isEqualTo("on_track");
    assertThat(house.path("successPct").asDouble()).isGreaterThan(90);
    assertThat(house.path("requiredPct").asDouble()).isEqualTo(75);
    assertThat(house.path("headline").asText()).startsWith("House: On track.");
    assertThat(house.path("previousVerdict").isNull()).isTrue();

    ok(api.post("/api/finance/goal-checks/run", Map.of()));
    JsonNode again = ok(api.get("/api/finance/goal-checks"));
    again.forEach(c -> assertThat(c.path("previousVerdict").asText()).isEqualTo(c.path("verdict").asText()));
  }

  @Test
  void taxComparesTheRegimesAndFlagsUnverifiedFigures() {
    ok(api.put("/api/finance/income", List.of(Map.of("personId", me, "takeHomeMonthly", 120000,
        "grossMonthly", 160000))));
    JsonNode tax = ok(api.get("/api/finance/tax/" + me));
    assertThat(tax.path("year").asText()).matches("\\d{4}-\\d{2}");
    assertThat(tax.path("comparison").path("newRegime").path("total").decimalValue()).isEqualByComparingTo("175760");
    assertThat(tax.path("comparison").path("oldStatus").asText()).isEqualTo("not_enough_data");
    assertThat(tax.path("comparison").path("needsVerification").asBoolean()).isTrue();
    assertThat(tax.path("unverified")).isNotEmpty();

    JsonNode withDeductions = ok(api.put("/api/finance/tax/" + me, Map.of("deductions",
        Map.of("section80C", 150000, "section80DSelf", 25000))));
    assertThat(withDeductions.path("comparison").path("oldRegime").path("total").decimalValue())
        .isEqualByComparingTo("333840");
    assertThat(withDeductions.path("comparison").path("cheaper").asText()).isEqualTo("new");
    assertThat(api.put("/api/finance/tax/" + me, Map.of("deductions", Map.of("crypto", 1))).status()).isEqualTo(400);
  }
}
