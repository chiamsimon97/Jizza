package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Kai Portfolio, Phase 2 through the API, with made-up figures only: Fixture A's three lots and
 * 30 / 30 / 40 earmarks, a goal's sale taking the oldest lot with a ₹12,000 long-term gain, the
 * short-goal warning for direct shares, a tree whose targets don't add up, a rule preview and a
 * lock that refuses a looser setting below it.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class FinanceHoldingsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;

  private ApiClient api;
  private String account;
  private String hdfc;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "holdings-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    account = ok(api.post("/api/finance/accounts", Map.of("kind", "demat", "provider", "Example Broker",
        "nickname", "Demat"))).path("id").asText();
    // The shared catalogue may already have it from another test run.
    JsonNode found = ok(api.get("/api/finance/instruments?q=INE040A01034"));
    hdfc = found.isEmpty()
        ? ok(api.post("/api/finance/instruments", Map.of("name", "HDFC Bank", "isin", "INE040A01034",
            "symbol", "HDFCBANK", "exchange", "NSE", "type", "stock", "assetClass", "equity"))).path("id").asText()
        : found.get(0).path("id").asText();
  }

  private JsonNode ok(ApiClient.Result r) {
    assertThat(r.ok()).as(r.body()).isTrue();
    return r.json();
  }

  private String goal(String type, String name, String targetDate) {
    return ok(api.post("/api/finance/goals", Map.of("type", type, "name", name, "targetAmountToday", 1000000,
        "targetDate", targetDate))).path("goal").path("id").asText();
  }

  private void buy(String date, int units, int price) {
    ok(api.post("/api/finance/holdings/purchases", Map.of("accountId", account, "instrumentId", hdfc,
        "date", date, "units", units, "pricePerUnit", price)));
  }

  private Map<String, Object> split(String goalId, int units) {
    return Map.of("goalId", goalId, "units", units);
  }

  @Test
  void fixtureAEarmarksThenAGoalsSaleUsesTheOldestLot() {
    String p1 = goal("home", "House", "2040-01-01");
    String p2 = goal("education", "Studies", "2041-01-01");
    String p3 = goal("retirement", "Retirement", "2050-01-01");
    // Out of order on purpose: FIFO is by date.
    buy("2026-09-15", 30, 1900);
    buy("2023-03-15", 40, 1600);
    buy("2024-08-15", 30, 1550);

    JsonNode holding = ok(api.get("/api/finance/holdings/" + account + "/" + hdfc));
    assertThat(holding.path("units").decimalValue()).isEqualByComparingTo("100");
    assertThat(holding.path("lots")).extracting(l -> l.path("acquiredOn").asText())
        .containsExactly("2023-03-15", "2024-08-15", "2026-09-15");
    assertThat(holding.path("saleNote").asText()).isEqualTo("A sale here uses the March 2023 lot first.");
    // Bought with no goal: all 100 units sit in Unassigned.
    assertThat(holding.path("earmarks")).hasSize(1);
    assertThat(holding.path("earmarks").get(0).path("unassigned").asBoolean()).isTrue();

    // A split that doesn't add up is refused and changes nothing.
    var wrong = api.put("/api/finance/earmarks", Map.of("accountId", account, "instrumentId", hdfc,
        "split", List.of(split(p1, 30), split(p2, 30), split(p3, 30))));
    assertThat(wrong.status()).isEqualTo(409);
    assertThat(wrong.body()).contains("90 units");

    ok(api.put("/api/finance/earmarks", Map.of("accountId", account, "instrumentId", hdfc,
        "split", List.of(split(p1, 30), split(p2, 30), split(p3, 40)))));

    // Retirement sells 30 at ₹2,000: FIFO takes 30 of the March 2023 lot, ₹12,000 long term.
    JsonNode sale = ok(api.post("/api/finance/holdings/sales", Map.of("accountId", account, "instrumentId", hdfc,
        "date", "2026-11-24", "units", 30, "proceeds", 60000, "goalId", p3)));
    assertThat(sale.path("parts")).hasSize(1);
    assertThat(sale.path("parts").get(0).path("acquiredOn").asText()).isEqualTo("2023-03-15");
    assertThat(sale.path("parts").get(0).path("term").asText()).isEqualTo("lt");
    assertThat(sale.path("gain").decimalValue()).isEqualByComparingTo("12000");

    JsonNode after = sale.path("holding");
    assertThat(after.path("lots")).extracting(l -> l.path("remaining").decimalValue().intValue())
        .containsExactly(10, 30, 30);
    Map<String, Integer> claims = new java.util.HashMap<>();
    after.path("earmarks").forEach(e -> claims.put(e.path("goalId").asText(), e.path("units").decimalValue().intValue()));
    assertThat(claims).containsEntry(p1, 30).containsEntry(p2, 30).containsEntry(p3, 10);

    JsonNode gains = ok(api.get("/api/finance/realized-gains?taxYear=2026-27"));
    assertThat(gains.path("longTerm").decimalValue()).isEqualByComparingTo("12000");
    assertThat(gains.path("rows").get(0).path("goal").asText()).isEqualTo("Retirement");

    // A goal cannot sell units it does not claim.
    var tooMany = api.post("/api/finance/holdings/sales", Map.of("accountId", account, "instrumentId", hdfc,
        "date", "2026-11-24", "units", 11, "proceeds", 22000, "goalId", p3));
    assertThat(tooMany.status()).isEqualTo(409);

    // The holdings list values from the latest price, or from cost when there is none yet.
    JsonNode list = ok(api.get("/api/finance/holdings"));
    assertThat(list.path("rows")).isNotEmpty();
  }

  @Test
  void sharesInAGoalUnderThreeYearsAwayNeedTheUsersChoice() {
    String trip = goal("travel", "Trip", java.time.LocalDate.now().plusYears(1).toString());
    buy("2024-01-10", 10, 1500);
    var warned = api.put("/api/finance/earmarks", Map.of("accountId", account, "instrumentId", hdfc,
        "split", List.of(split(trip, 10))));
    assertThat(warned.status()).isEqualTo(409);
    assertThat(warned.json().path("type").asText()).endsWith("finance-short-goal-shares");

    JsonNode kept = ok(api.put("/api/finance/earmarks", Map.of("accountId", account, "instrumentId", hdfc,
        "split", List.of(split(trip, 10)), "keepShares", true)));
    JsonNode mark = kept.path("earmarks").get(0);
    assertThat(mark.path("shortGoal").asBoolean()).isTrue();
    assertThat(mark.path("yourChoice").asBoolean()).isTrue();
    assertThat(ok(api.get("/api/finance/tree?goal=" + trip)).toString()).contains("Kept by your choice");
  }

  @Test
  void aTreeMustAddUpAndALockRefusesALooserRuleBelowIt() {
    String home = goal("home", "House", "2040-01-01");
    buy("2023-03-15", 40, 1600);
    ok(api.put("/api/finance/earmarks", Map.of("accountId", account, "instrumentId", hdfc,
        "split", List.of(split(home, 40)))));

    JsonNode tree = ok(api.get("/api/finance/tree?goal=" + home));
    assertThat(tree.path("saved").asBoolean()).isFalse();
    ArrayNode nodes = (ArrayNode) tree.path("nodes").deepCopy();
    ObjectNode top = null;
    for (JsonNode n : nodes) {
      if (n.path("parentId").isNull() || n.path("parentId").asText().isEmpty()) {
        top = (ObjectNode) n;
        break;
      }
    }
    assertThat(top).isNotNull();
    top.put("targetPct", top.path("targetPct").decimalValue().add(java.math.BigDecimal.ONE));
    var bad = api.put("/api/finance/tree/" + home, Json.obj().set("nodes", nodes));
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.json().path("type").asText()).endsWith("finance-tree-sum");

    JsonNode saved = ok(api.put("/api/finance/tree/" + home, Json.obj().set("nodes", tree.path("nodes"))));
    assertThat(saved.path("saved").asBoolean()).isTrue();
    JsonNode allocation = ok(api.get("/api/finance/allocation?goal=" + home));
    assertThat(allocation.path("rows")).isNotEmpty();

    // A stop-loss for the whole goal reaches its one earmark.
    Map<String, Object> stopLoss = Map.of("level", "goal", "goalId", home, "field", "stop_loss", "state", "set",
        "params", Map.of("kind", "fixed", "pct", 15));
    JsonNode preview = ok(api.post("/api/finance/rules/preview", stopLoss));
    assertThat(preview.path("affectedEarmarks").asInt()).isEqualTo(1);

    // Observe, locked at the goal: a holding below cannot be set to propose.
    ok(api.put("/api/finance/rules", Map.of("level", "goal", "goalId", home, "field", "execution_mode",
        "state", "set", "locked", true, "params", Map.of("mode", "observe"))));
    var looser = api.put("/api/finance/rules", Map.of("level", "instrument", "goalId", home, "instrumentId", hdfc,
        "field", "execution_mode", "state", "set", "params", Map.of("mode", "propose")));
    assertThat(looser.status()).isEqualTo(400);
    assertThat(looser.body()).contains("can't be loosened");
    var auto = api.put("/api/finance/rules", Map.of("level", "user", "field", "execution_mode",
        "state", "set", "params", Map.of("mode", "auto")));
    assertThat(auto.status()).isEqualTo(400);

    JsonNode effective = ok(api.get("/api/finance/rules/effective?goal=" + home + "&instrument=" + hdfc));
    List<String> fields = new ArrayList<>();
    effective.path("fields").forEach(f -> fields.add(f.path("field").asText()));
    assertThat(fields).contains("execution_mode", "stop_loss");
  }

  @Test
  void anInstrumentSomeoneElseAddedCannotBeChanged() {
    var change = api.patch("/api/finance/instruments/" + hdfc, Map.of("capBucket", "small"));
    // Either another run added it (not ours) or we did and nobody else holds it yet.
    JsonNode mine = ok(api.get("/api/finance/instruments?q=HDFCBANK"));
    if (!mine.get(0).path("yours").asBoolean()) assertThat(change.status()).isEqualTo(404);
    assertThat(mine.get(0).has("addedBy")).isFalse();
  }
}
