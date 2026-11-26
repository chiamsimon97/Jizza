package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Kai part A through the API: a broker's MCP server added on Connectors (a stub that answers as
 * Zerodha's Kite does, with made-up holdings) is linked to a demat account. A sync waits for the
 * sign-in, reads only the holdings tools, and lands as a batch the person reviews and saves; the
 * same holdings again change nothing.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class FinanceSyncE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  static final String SHARES = """
      [{"tradingsymbol":"EXAMPLECO","exchange":"NSE","isin":"INE000A01011","quantity":10,"t1_quantity":2,
        "average_price":1500,"last_price":1600,"close_price":1590},
       {"tradingsymbol":"SOLDCO","exchange":"NSE","isin":"INE000B01012","quantity":0,"t1_quantity":0,
        "average_price":90,"last_price":100}]""";
  static final String FUNDS = """
      [{"folio":"<redacted>","fund":"EXAMPLE FLEXI CAP FUND - DIRECT PLAN","tradingsymbol":"INF000X01AA1",
        "average_price":50,"last_price":60,"quantity":100.5}]""";

  static final String MARGINS = """
      {"equity":{"enabled":true,"net":4800.5,"available":{"cash":5000.25,"live_balance":5000.25,
        "opening_balance":5000.25}},"commodity":{"enabled":false,"net":0}}""";

  @LocalServerPort private int port;
  @org.springframework.beans.factory.annotation.Autowired
  private com.jizza.platform.usecase.finance.tools.FinanceToolProvider kaiTools;
  @org.springframework.beans.factory.annotation.Autowired
  private com.jizza.engine.api.orchestrator.AgentDirectory agents;
  private UUID userId;

  private ApiClient api;
  private StubMcpServer kite;

  @BeforeEach
  void setUp() {
    kite = new StubMcpServer();
    kite.kiteShares = SHARES;
    kite.kiteFunds = FUNDS;
    api = new ApiClient(port);
    api.prime();
    String email = "sync-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(login.ok()).isTrue();
    userId = UUID.fromString(login.json().path("userId").asText());
    api.post("/api/auth/step-up", Map.of("password", PASSWORD));
    api.get("/api/finance/profile");
  }

  @AfterEach
  void tearDown() {
    kite.close();
  }

  private JsonNode ok(ApiClient.Result r) {
    assertThat(r.ok()).as(r.body()).isTrue();
    return r.json();
  }

  @Test
  void aSyncWaitsForSignInThenLandsAsABatchToReview() {
    Map<String, Object> body = new HashMap<>();
    body.put("name", "Kite");
    body.put("url", kite.url());
    body.put("authType", "NONE");
    String serverId = ok(api.post("/api/mcp-servers", body)).path("id").asText();
    String demat = ok(api.post("/api/finance/accounts", Map.of("kind", "demat", "provider", "Example Broker")))
        .path("id").asText();
    String savings = ok(api.post("/api/finance/accounts", Map.of("kind", "savings"))).path("id").asText();

    JsonNode view = ok(api.get("/api/finance/sources"));
    assertThat(view.path("servers")).hasSize(1);
    assertThat(view.path("servers").get(0).path("funds").asBoolean()).isTrue();
    assertThat(view.path("servers").get(0).path("placesOrders").asBoolean()).isTrue();
    assertThat(api.post("/api/finance/sources", Map.of("serverId", serverId, "accountId", savings)).status()).isEqualTo(400);
    String source = ok(api.post("/api/finance/sources", Map.of("serverId", serverId, "accountId", demat))).path("id").asText();
    assertThat(api.post("/api/finance/sources", Map.of("serverId", serverId, "accountId", demat)).status()).isEqualTo(409);

    // Kite signs in inside its session: until the login tool has run, the sync asks for it.
    JsonNode waiting = ok(api.post("/api/finance/sources/" + source + "/sync", Map.of()));
    assertThat(waiting.path("status").asText()).isEqualTo("needs_sign_in");
    assertThat(kite.calls).noneMatch(c -> c.startsWith("get_"));

    ok(api.post("/api/mcp-servers/" + serverId + "/tools/login/test", Json.obj()));
    JsonNode synced = ok(api.post("/api/finance/sources/" + source + "/sync", Map.of()));
    assertThat(synced.path("status").asText()).isEqualTo("to_review");
    assertThat(synced.path("holdings").asInt()).isEqualTo(2);
    // Only the holdings tools were called; nothing that changes the account.
    assertThat(kite.calls).allMatch(c -> c.startsWith("login") || c.startsWith("get_holdings") || c.startsWith("get_mf_holdings"));

    String batchId = synced.path("batchId").asText();
    JsonNode batch = ok(api.get("/api/finance/imports/" + batchId));
    assertThat(batch.path("status").asText()).isEqualTo("parsed");
    assertThat(batch.path("stats").path("source").asText()).isEqualTo("Kite");
    JsonNode rows = batch.path("stats").path("holdings");
    assertThat(rows).hasSize(2);
    BigDecimal before = ok(api.get("/api/finance/net-worth")).path("assets").decimalValue();
    ok(api.post("/api/finance/imports/" + batchId + "/commit", Map.of()));
    // 12 × 1,600 + 100.5 × 60
    assertThat(ok(api.get("/api/finance/net-worth")).path("assets").decimalValue())
        .isEqualByComparingTo(before.add(new BigDecimal("25230")));

    JsonNode again = ok(api.post("/api/finance/sources/" + source + "/sync", Map.of()));
    assertThat(again.path("status").asText()).isEqualTo("no_change");
    JsonNode listed = ok(api.get("/api/finance/sources")).path("sources").get(0);
    assertThat(listed.path("lastStatus").asText()).isEqualTo("no_change");
    assertThat(listed.path("signedIn").asBoolean()).isTrue();

    // Another account can't see or sync it.
    ApiClient other = new ApiClient(port);
    other.prime();
    String email = "sync-other-" + UUID.randomUUID() + "@jizza.local";
    other.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    other.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(other.post("/api/finance/sources/" + source + "/sync", Map.of()).status()).isEqualTo(404);
    assertThat(ok(other.get("/api/finance/sources")).path("servers")).isEmpty();

    assertThat(api.delete("/api/finance/sources/" + source).status()).isEqualTo(204);
    assertThat(ok(api.get("/api/finance/sources")).path("sources")).isEmpty();
  }

  @Test
  void askingKaiToSyncFillsThePortfolioWithNoMappingByHand() {
    kite.kiteMargins = MARGINS;
    Map<String, Object> body = new HashMap<>();
    body.put("name", "Zerodha Kite");
    body.put("url", kite.url());
    body.put("authType", "NONE");
    String serverId = ok(api.post("/api/mcp-servers", body)).path("id").asText();
    ok(api.post("/api/mcp-servers/" + serverId + "/tools/login/test", Json.obj()));
    UUID kai = agents.byKey(userId, "finance").orElseThrow().id();
    BigDecimal before = ok(api.get("/api/finance/net-worth")).path("assets").decimalValue();

    // No demat account and no link yet: Kai makes both and lands a review.
    var first = kaiTools.invoke(kai, new com.jizza.engine.api.tool.ToolCall("finance_sync_connected", Json.obj()));
    assertThat(first.ok()).as(String.valueOf(first.error())).isTrue();
    JsonNode found = first.output().path("brokers").get(0);
    assertThat(found.path("status").asText()).isEqualTo("to_review");
    assertThat(found.path("accountCreated").asBoolean()).isTrue();
    assertThat(found.path("linkedNow").asBoolean()).isTrue();
    assertThat(found.path("account").asText()).isEqualTo("Zerodha");
    assertThat(found.path("shares").asInt()).isEqualTo(1);
    assertThat(found.path("mutualFunds").asInt()).isEqualTo(1);
    assertThat(found.path("cash").decimalValue()).isEqualByComparingTo("5000.25");
    assertThat(first.output().path("review").path("url").asText()).endsWith("view=connect");
    // Nothing saved until the person says so.
    assertThat(ok(api.get("/api/finance/net-worth")).path("assets").decimalValue()).isEqualByComparingTo(before);
    JsonNode accounts = ok(api.get("/api/finance/accounts"));
    assertThat(accounts).filteredOn(a -> "demat".equals(a.path("kind").asText())).hasSize(1)
        .allMatch(a -> "Zerodha".equals(a.path("provider").asText()));
    assertThat(ok(api.get("/api/finance/sources")).path("sources")).hasSize(1);

    // A file import can't be saved from chat; the sync can.
    var saved = kaiTools.invoke(kai, new com.jizza.engine.api.tool.ToolCall("finance_save_sync",
        Json.obj().put("batchId", found.path("batchId").asText())));
    assertThat(saved.ok()).as(String.valueOf(saved.error())).isTrue();
    assertThat(saved.output().path("status").asText()).isEqualTo("committed");
    // 12 × 1,600 + 100.5 × 60, and the 5,000.25 left in the account (net worth is in whole rupees)
    assertThat(ok(api.get("/api/finance/net-worth")).path("assets").decimalValue())
        .isEqualByComparingTo(before.add(new BigDecimal("30230")));

    // Asked again: the same account, nothing new; only read tools were ever called.
    var again = kaiTools.invoke(kai, new com.jizza.engine.api.tool.ToolCall("finance_sync_connected",
        Json.obj().put("broker", "zerodha")));
    JsonNode second = again.output().path("brokers").get(0);
    assertThat(second.path("status").asText()).isEqualTo("no_change");
    assertThat(second.path("linkedNow").asBoolean()).isFalse();
    assertThat(kite.calls).allMatch(c -> c.startsWith("login") || c.startsWith("get_holdings")
        || c.startsWith("get_mf_holdings") || c.startsWith("get_margins"));
    var none = kaiTools.invoke(kai, new com.jizza.engine.api.tool.ToolCall("finance_sync_connected",
        Json.obj().put("broker", "groww")));
    assertThat(none.ok()).isFalse();
  }

  @Test
  void kaiUsesTheDematAccountAlreadyAtThatBroker() {
    Map<String, Object> body = new HashMap<>();
    body.put("name", "Kite");
    body.put("url", kite.url());
    body.put("authType", "NONE");
    String serverId = ok(api.post("/api/mcp-servers", body)).path("id").asText();
    ok(api.post("/api/mcp-servers/" + serverId + "/tools/login/test", Json.obj()));
    ok(api.post("/api/finance/accounts", Map.of("kind", "demat", "provider", "Other Broker")));
    String zerodha = ok(api.post("/api/finance/accounts", Map.of("kind", "demat", "provider", "Zerodha")))
        .path("id").asText();
    UUID kai = agents.byKey(userId, "finance").orElseThrow().id();
    var r = kaiTools.invoke(kai, new com.jizza.engine.api.tool.ToolCall("finance_sync_connected", Json.obj()));
    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.output().path("brokers").get(0).path("accountCreated").asBoolean()).isFalse();
    assertThat(ok(api.get("/api/finance/sources")).path("sources").get(0).path("accountId").asText()).isEqualTo(zerodha);
    // No cash tool on this server: holdings still sync.
    assertThat(r.output().path("brokers").get(0).has("cash")).isFalse();
  }

  @Test
  void anOrderIsPreparedWithItsTaxEffectAndPlacedOnlyWithThePassword() {
    Map<String, Object> body = new HashMap<>();
    body.put("name", "Kite");
    body.put("url", kite.url());
    body.put("authType", "NONE");
    String serverId = ok(api.post("/api/mcp-servers", body)).path("id").asText();
    String demat = ok(api.post("/api/finance/accounts", Map.of("kind", "demat", "provider", "Example Broker")))
        .path("id").asText();
    String source = ok(api.post("/api/finance/sources", Map.of("serverId", serverId, "accountId", demat))).path("id").asText();
    ok(api.post("/api/mcp-servers/" + serverId + "/tools/login/test", Json.obj()));
    String batchId = ok(api.post("/api/finance/sources/" + source + "/sync", Map.of())).path("batchId").asText();
    ok(api.post("/api/finance/imports/" + batchId + "/commit", Map.of()));

    JsonNode holdings = ok(api.get("/api/finance/holdings"));
    String share = null;
    String fund = null;
    for (JsonNode h : holdings.path("rows")) {
      if ("INE000A01011".equals(h.path("isin").asText())) share = h.path("instrumentId").asText();
      if ("INF000X01AA1".equals(h.path("isin").asText())) fund = h.path("instrumentId").asText();
    }
    assertThat(share).as(holdings.toString()).isNotNull();

    Map<String, Object> sell = new HashMap<>(Map.of("accountId", demat, "instrumentId", share, "side", "sell",
        "quantity", 5, "orderType", "market"));
    JsonNode preview = ok(api.post("/api/finance/orders/prepare", sell));
    assertThat(preview.path("symbol").asText()).isEqualTo("EXAMPLECO");
    assertThat(preview.path("held").decimalValue()).isEqualByComparingTo("12");
    assertThat(preview.path("lots")).isNotEmpty();
    assertThat(preview.path("canPlace").asBoolean()).as(preview.toString()).isTrue();
    assertThat(preview.path("placeVia").asText()).isEqualTo("Kite");
    sell.put("quantity", 50);
    assertThat(api.post("/api/finance/orders/prepare", sell).status()).isEqualTo(400);
    sell.put("quantity", 5);

    if (fund != null) {
      JsonNode mf = ok(api.post("/api/finance/orders/prepare", Map.of("accountId", demat, "instrumentId", fund,
          "side", "buy", "quantity", 1, "orderType", "market")));
      assertThat(mf.path("canPlace").asBoolean()).isFalse();
    }

    // No password, or a wrong one: nothing reaches the broker.
    assertThat(api.post("/api/finance/orders/place", sell).status()).isEqualTo(400);
    sell.put("password", "wrong-password-1");
    assertThat(api.post("/api/finance/orders/place", sell).ok()).isFalse();
    assertThat(kite.calls).noneMatch(c -> c.startsWith("place_order"));

    sell.put("password", PASSWORD);
    JsonNode placed = ok(api.post("/api/finance/orders/place", sell));
    assertThat(placed.path("status").asText()).isEqualTo("sent");
    assertThat(placed.path("brokerOrderId").asText()).isEqualTo("151220000000000");
    assertThat(kite.calls).anyMatch(c -> c.startsWith("place_order") && c.contains("\"transaction_type\":\"SELL\"")
        && c.contains("\"tradingsymbol\":\"EXAMPLECO\"") && c.contains("\"quantity\":5"));
    // The lots wait for the next sync.
    assertThat(ok(api.post("/api/finance/orders/prepare", sell)).path("held").decimalValue()).isEqualByComparingTo("12");
    assertThat(ok(api.get("/api/finance/orders")).get(0).path("status").asText()).isEqualTo("sent");
  }
}
