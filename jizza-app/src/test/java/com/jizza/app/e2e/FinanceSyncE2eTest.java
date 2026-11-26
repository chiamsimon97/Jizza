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

  @LocalServerPort private int port;

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
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
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
}
