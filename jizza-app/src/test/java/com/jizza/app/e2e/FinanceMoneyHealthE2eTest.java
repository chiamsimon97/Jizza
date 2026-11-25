package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Kai Portfolio, Phase 1a through the API: Quick start saving step by step, the Money Health
 * check and action plan for Fixture D's household, every step skipped, loans with gold and family
 * borrowing, cash flow, net worth, protection, wills, alerts muted in Settings and the export.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class FinanceMoneyHealthE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;

  private ApiClient api;
  private String me;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "money-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    me = api.get("/api/finance/profile").json().path("people").get(0).path("id").asText();
  }

  private JsonNode step(String step, Object answers) {
    var r = api.post("/api/finance/quick-start", Map.of("step", step, "answers", answers));
    assertThat(r.ok()).as(step + ": " + r.body()).isTrue();
    return r.json().path("progress");
  }

  /** Fixture D's household through Quick start steps 1 to 6. */
  private void fixtureD() {
    JsonNode p = step("people", Map.of("me", Map.of("dob", "1992-06-01"), "people", List.of(
        Map.of("displayName", "Spouse", "kind", "spouse", "type", "individual", "dob", "1994-06-01",
            "isEarning", true),
        Map.of("displayName", "Child", "kind", "child", "type", "minor", "dob", "2020-06-01",
            "isDependent", true))));
    assertThat(p.path("next").asText()).isEqualTo("income");
    String spouse = person("spouse");
    String child = person("child");
    step("income", Map.of("incomes", List.of(
        Map.of("personId", me, "takeHomeMonthly", 120000, "grossMonthly", 160000),
        Map.of("personId", spouse, "takeHomeMonthly", 60000, "grossMonthly", 75000))));
    step("spending", Map.of("essentialMonthly", 40000, "otherMonthly", 50000, "investingMonthly", 30000));
    step("loans", Map.of("loans", List.of(
        Map.of("kind", "HOME", "outstanding", 3500000, "monthlyPayment", 30000, "rateInput", "8.5%"),
        Map.of("kind", "PERSONAL", "outstanding", 150000, "monthlyPayment", 8000, "rateInput", "14% a year"))));
    step("insurance", Map.of("policies", List.of(
        Map.of("kind", "term", "sumAssured", 5000000),
        Map.of("kind", "health", "sumAssured", 500000, "employerProvided", true,
            "coveredPersonIds", List.of(me, spouse, child)))));
    JsonNode savings = step("savings", Map.of("totals", List.of(
        Map.of("kind", "savings_balance", "value", 250000, "isEmergency", true))));
    assertThat(savings.path("next").asText()).isEqualTo("goals");
    // Steps 7 and 8 (Phase 1b): no goals yet, so Goal funding stays unknown; the risk questions.
    var skipped = api.post("/api/finance/quick-start", Map.of("step", "goals", "skip", true));
    assertThat(skipped.ok()).as(skipped.body()).isTrue();
    JsonNode done = step("risk", Map.of("answers", List.of(2, 2, 2, 2, 2, 2)));
    assertThat(done.path("done").asBoolean()).isTrue();
    assertThat(done.path("total").asInt()).isEqualTo(8);
  }

  private String person(String kind) {
    for (JsonNode p : api.get("/api/finance/people").json()) {
      if (kind.equals(p.path("kind").asText())) return p.path("id").asText();
    }
    throw new AssertionError("no " + kind);
  }

  private static JsonNode check(JsonNode health, String key) {
    for (JsonNode c : health.path("checks")) if (key.equals(c.path("key").asText())) return c;
    throw new AssertionError("no check " + key);
  }

  @Test
  void quickStartGivesFixtureDsChecksAndTopThreeActions() {
    fixtureD();
    var health = api.get("/api/finance/health");
    assertThat(health.ok()).as(health.body()).isTrue();
    JsonNode h = health.json();

    assertThat(check(h, "emergencyFund").path("status").asText()).isEqualTo("amber");
    assertThat(check(h, "emergencyFund").path("shown").decimalValue()).isEqualByComparingTo("3.2");
    assertThat(check(h, "debt").path("status").asText()).isEqualTo("red");
    assertThat(check(h, "savingsRate").path("shown").decimalValue()).isEqualByComparingTo("16.7");
    assertThat(check(h, "protection").path("shown").decimalValue()).isEqualByComparingTo("3");
    assertThat(check(h, "spendingControl").path("status").asText()).isEqualTo("green");
    // Goals arrive in Phase 1b, so Goal funding is unknown and the score is over 85 points:
    // (10 + 0 + 10 + 0 + 10) / 85 × 100 = 35.3.
    assertThat(check(h, "goalFunding").path("status").asText()).isEqualTo("unknown");
    assertThat(h.path("score").asInt()).isEqualTo(35);
    assertThat(h.path("surplus").decimalValue()).isEqualByComparingTo("22000");
    assertThat(h.path("flags").path("willMissing").asBoolean()).isTrue();

    List<String> top = new ArrayList<>();
    h.path("topActions").forEach(a -> top.add(a.path("actionKey").asText()));
    assertThat(top).containsExactly("debt.high_interest", "protection.gaps", "emergency.full");
    assertThat(h.path("topActions").get(0).path("title").asText())
        .isEqualTo("Repay the ₹1.5 lakh personal loan at 14%");

    var profile = api.get("/api/finance/profile").json();
    assertThat(profile.path("setupProgress").path("quickStart").asText()).isEqualTo("done");
    var saved = api.get("/api/finance/quick-start").json();
    assertThat(saved.path("answers").path("spending").path("otherMonthly").asInt()).isEqualTo(50000);
  }

  @Test
  void answeringAStepAgainReplacesWhatItWrote() {
    fixtureD();
    assertThat(api.get("/api/finance/loans").json()).hasSize(2);
    step("loans", Map.of("loans", List.of(
        Map.of("kind", "HOME", "outstanding", 3500000, "monthlyPayment", 30000, "rateInput", "8.5%"))));
    JsonNode loans = api.get("/api/finance/loans").json();
    assertThat(loans).hasSize(1);
    var h = api.get("/api/finance/health").json();
    assertThat(check(h, "debt").path("status").asText()).isEqualTo("green");
    // The repaid personal loan's action closed itself.
    List<String> keys = new ArrayList<>();
    api.get("/api/finance/actions").json().forEach(a -> {
      if (List.of("open", "later").contains(a.path("status").asText())) keys.add(a.path("actionKey").asText());
    });
    assertThat(keys).doesNotContain("debt.high_interest");
  }

  @Test
  void everyStepSkippedStillGivesAResultWithUnknownChecks() {
    for (String s : List.of("people", "income", "spending", "loans", "insurance", "savings", "goals", "risk")) {
      var r = api.post("/api/finance/quick-start", Map.of("step", s, "skip", true));
      assertThat(r.ok()).as(r.body()).isTrue();
    }
    JsonNode h = api.get("/api/finance/health").json();
    h.path("checks").forEach(c -> assertThat(c.path("status").asText()).isEqualTo("unknown"));
    assertThat(h.path("score").isNull()).isTrue();
    assertThat(api.get("/api/finance/quick-start").json().path("progress").path("done").asBoolean()).isTrue();
  }

  @Test
  void actionsAreDoneSnoozedOrNotForMeAndThreeAtMostAreOpen() {
    fixtureD();
    JsonNode actions = api.get("/api/finance/actions").json();
    long open = 0;
    for (JsonNode a : actions) if ("open".equals(a.path("status").asText())) open++;
    assertThat(open).isEqualTo(3);

    String first = actions.get(0).path("id").asText();
    assertThat(api.patch("/api/finance/actions/" + first, Map.of("status", "dismissed")).status()).isEqualTo(400);
    assertThat(api.patch("/api/finance/actions/" + first, Map.of("status", "snoozed",
        "snoozedUntil", LocalDate.now().minusDays(1).toString())).status()).isEqualTo(400);
    var snoozed = api.patch("/api/finance/actions/" + first, Map.of("status", "snoozed",
        "snoozedUntil", LocalDate.now().plusDays(10).toString()));
    assertThat(snoozed.json().path("status").asText()).as(snoozed.body()).isEqualTo("snoozed");

    String will = null;
    for (JsonNode a : api.get("/api/finance/actions").json()) {
      if ("hygiene.will".equals(a.path("actionKey").asText())) will = a.path("id").asText();
    }
    assertThat(will).isNotNull();
    var dismissed = api.patch("/api/finance/actions/" + will, Map.of("status", "dismissed",
        "reason", "Already with my lawyer"));
    assertThat(dismissed.json().path("status").asText()).isEqualTo("dismissed");
    api.get("/api/finance/health");
    long liveWill = 0;
    for (JsonNode a : api.get("/api/finance/actions").json()) {
      if ("hygiene.will".equals(a.path("actionKey").asText()) && !"dismissed".equals(a.path("status").asText())) liveWill++;
    }
    assertThat(liveWill).as("a dismissed action does not come back").isZero();
  }

  @Test
  void loansNormaliseRatesTrackGoldLtvAndPlanRepayment() {
    api.put("/api/finance/income", List.of(Map.of("personId", me, "takeHomeMonthly", 70000)));
    var card = api.post("/api/finance/loans", Map.of("kind", "CREDIT_CARD", "outstanding", 60000, "rateInput", "3.5% a month"));
    assertThat(card.ok()).as(card.body()).isTrue();
    assertThat(card.json().path("effectiveAnnualRate").decimalValue()).isEqualByComparingTo("42");
    assertThat(card.json().path("repaymentMode").asText()).isEqualTo("revolving");

    var gold = api.post("/api/finance/loans", Map.of("kind", "GOLD_LOAN", "outstanding", 200000,
        "principalTaken", 200000, "rateInput", "9.5%", "startDate", "2026-06-01",
        "maturityOrPromisedDate", "2027-05-31",
        "pledges", List.of(Map.of("assetKind", "gold", "grams", 30, "purityKarat", 22, "valuation", 280000))));
    assertThat(gold.ok()).as(gold.body()).isTrue();
    JsonNode ltv = gold.json().path("ltv");
    assertThat(ltv.path("pct").decimalValue()).isEqualByComparingTo("78.2");
    assertThat(ltv.path("capPct").decimalValue()).isEqualByComparingTo("85");
    assertThat(ltv.path("warn").asBoolean()).isFalse();
    assertThat(ltv.path("needsVerification").asBoolean()).isTrue();
    assertThat(ltv.path("detail").path("maturityAmount").decimalValue()).isEqualByComparingTo("218948");

    var family = api.post("/api/finance/loans", Map.of("kind", "FAMILY_FRIEND", "lenderName", "Brother",
        "outstanding", 100000, "rateInput", "no interest", "maturityOrPromisedDate", "2027-03-31"));
    assertThat(family.json().path("effectiveAnnualRate").decimalValue()).isEqualByComparingTo("0");
    assertThat(family.json().path("isPrivate").asBoolean()).isTrue();
    assertThat(family.json().path("label").asText()).isEqualTo("Brother");
    assertThat(family.json().path("sharedLabel").asText()).isEqualTo("Family / friend");

    var lender = api.post("/api/finance/loans", Map.of("kind", "MONEYLENDER", "outstanding", 20000,
        "rateInput", "₹2 per ₹100 per month"));
    assertThat(lender.json().path("effectiveAnnualRate").decimalValue()).isEqualByComparingTo("24");
    assertThat(api.post("/api/finance/loans", Map.of("kind", "PERSONAL", "outstanding", 1,
        "rateInput", "about half")).status()).isEqualTo(400);
    assertThat(api.post("/api/finance/loans", Map.of("kind", "PAYDAY", "outstanding", 1)).status()).isEqualTo(400);

    var plan = api.get("/api/finance/loans/plan");
    assertThat(plan.ok()).as(plan.body()).isTrue();
    JsonNode order = plan.json().path("plan").path("order");
    assertThat(order.get(0).path("step").asText()).isEqualTo("pay_in_full");
    // Card (42%) and moneylender (24%) are paid in full, highest rate first.
    assertThat(order.get(0).path("loanId").asText()).isEqualTo(card.json().path("id").asText());
    assertThat(order.get(1).path("loanId").asText()).isEqualTo(lender.json().path("id").asText());
    assertThat(order.get(1).path("step").asText()).isEqualTo("pay_in_full");
    assertThat(plan.json().path("monthlySurplus").decimalValue()).isEqualByComparingTo("70000");
    assertThat(api.get("/api/finance/loans/plan?method=sideways").status()).isEqualTo(400);

    var payment = api.post("/api/finance/loans/" + card.json().path("id").asText() + "/payments",
        Map.of("dueOrPaidOn", "2026-12-05", "amount", 60000, "principalPart", 60000));
    assertThat(payment.ok()).as(payment.body()).isTrue();
    var paidCard = api.get("/api/finance/loans/" + card.json().path("id").asText()).json();
    assertThat(paidCard.path("status").asText()).isEqualTo("closed");
  }

  @Test
  void cashFlowNetWorthProtectionAndWill() {
    fixtureD();
    JsonNode cf = api.get("/api/finance/cash-flow").json();
    assertThat(cf.path("surplus").decimalValue()).isEqualByComparingTo("22000");
    assertThat(cf.path("spendByBucket").path("need").decimalValue()).isEqualByComparingTo("78000");
    assertThat(cf.path("surplusUse").get(0).path("key").asText()).isEqualTo("debt");
    assertThat(api.get("/api/finance/cash-flow?month=November").status()).isEqualTo(400);

    JsonNode nw = api.get("/api/finance/net-worth").json();
    assertThat(nw.path("assets").decimalValue()).isEqualByComparingTo("250000");
    assertThat(nw.path("liabilities").decimalValue()).isEqualByComparingTo("3650000");
    assertThat(api.get("/api/finance/net-worth?person=" + UUID.randomUUID()).status()).isEqualTo(404);

    JsonNode protection = api.get("/api/finance/protection").json();
    assertThat(protection.path("protection").path("gaps")).hasSize(3);

    var will = api.put("/api/finance/estate/" + me, Map.of("willStatus", "registered", "willDate", "2025-01-10"));
    assertThat(will.ok()).as(will.body()).isTrue();
    assertThat(api.get("/api/finance/estate/" + me).json().path("willStatus").asText()).isEqualTo("registered");
    assertThat(api.get("/api/finance/health").json().path("flags").path("willMissing").asBoolean()).isFalse();
  }

  @Test
  void formsRefuseUnknownFieldsAndBadValues() {
    assertThat(api.post("/api/finance/accounts", Map.of("kind", "savings", "userId", UUID.randomUUID().toString())).status())
        .isEqualTo(400);
    assertThat(api.post("/api/finance/assets", Map.of("kind", "fd", "value", -5)).status()).isEqualTo(400);
    assertThat(api.post("/api/finance/policies", Map.of("kind", "term")).status()).isEqualTo(400);
    var asset = api.post("/api/finance/assets", Map.of("kind", "ppf", "value", 120000.5));
    assertThat(asset.ok()).as(asset.body()).isTrue();
    assertThat(asset.json().path("assetClass").asText()).isEqualTo("debt");
    assertThat(asset.json().path("personId").asText()).isEqualTo(me);
    assertThat(api.put("/api/finance/budget", List.of(Map.of("categoryId", UUID.randomUUID().toString(),
        "monthlyAmount", 1))).status()).isEqualTo(404);
    assertThat(api.post("/api/finance/quick-start", Map.of("step", "goals", "answers", Map.of())).status()).isEqualTo(400);
  }

  @Test
  void alertsCanBeMutedAndTheExportCarriesTheRecords() {
    var s = api.patch("/api/finance/profile/settings", Map.of("mutedAlerts", List.of("policy_renewal")));
    assertThat(s.json().path("mutedAlerts").get(0).asText()).as(s.body()).isEqualTo("policy_renewal");
    assertThat(api.patch("/api/finance/profile/settings", Map.of("mutedAlerts", List.of("lottery"))).status())
        .isEqualTo(400);

    fixtureD();
    assertThat(api.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    JsonNode finance = api.get("/api/data/export").json().path("finance");
    assertThat(finance.path("records").path("fin_liability")).hasSize(2);
    assertThat(finance.path("records").path("fin_insurance_policy")).hasSize(2);
  }
}
