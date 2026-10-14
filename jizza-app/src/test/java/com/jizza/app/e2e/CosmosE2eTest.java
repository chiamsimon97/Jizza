package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.util.Json;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Acceptance for the Memory Cosmos through the HTTP API and the real chat path: the bodies and
 * their links, read stamps from a real delegated turn, sharing, restriction, moving notes, step-up
 * on the consequential actions, live updates, and isolation between users.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class CosmosE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  @Autowired private com.jizza.engine.api.knowledge.KnowledgeStore knowledge;

  private ApiClient api;
  private String email;
  private String priyaId;
  private String accountId;
  private String connectionId;
  private String sipId;
  private String allergyId;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();
    email = "cosmos-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/step-up", Map.of("password", PASSWORD));

    var connection = api.post("/api/connections", Map.of(
        "serviceName", "Zerodha", "authScheme", "API_KEY", "clientSecret", "k",
        "baseUrl", "https://api.kite.example.com"));
    assertThat(connection.ok()).as(connection.body()).isTrue();
    connectionId = connection.json().path("id").asText();

    priyaId = api.post("/api/memory/subjects", Map.of("kind", "PERSON", "name", "Priya",
        "relation", "wife")).json().path("id").asText();
    accountId = api.post("/api/memory/subjects", Map.of("kind", "ACCOUNT",
        "name", "Priya's Zerodha account", "connectionId", connectionId,
        "domains", List.of("finance"))).json().path("id").asText();
    api.post("/api/memory/subjects/" + accountId + "/relations",
        Map.of("toSubjectId", priyaId, "relation", "held_by"));

    remember("RELATIONSHIP", "Priya is the user's wife and works as a school teacher", null);
    sipId = remember("FINANCE", "Priya invests Rs 20,000 a month in a Nifty 50 index fund SIP", "monthly_sip");
    remember("FINANCE", "Priya's risk tolerance is moderate", "risk_tolerance");
    allergyId = remember("HEALTH", "Priya is allergic to penicillin", null);
  }

  private String remember(String category, String content, String key) {
    var body = new HashMap<String, Object>(Map.of(
        "category", category, "content", content, "subjectIds", List.of(priyaId)));
    if (key != null) body.put("key", key);
    var result = api.post("/api/memory", body);
    assertThat(result.ok()).as(result.body()).isTrue();
    return result.json().path("memory").path("id").asText();
  }

  private String agentId(String key) {
    for (JsonNode a : api.get("/api/agents").json()) {
      if (a.path("key").asText().equals(key)) return a.path("id").asText();
    }
    throw new AssertionError("no agent " + key);
  }

  private Map<String, JsonNode> bodies() {
    var snapshot = api.get("/api/memory/cosmos");
    assertThat(snapshot.ok()).as(snapshot.body()).isTrue();
    Map<String, JsonNode> out = new HashMap<>();
    for (JsonNode b : snapshot.json().path("bodies")) out.put(b.path("bodyId").asText(), b);
    return out;
  }

  private JsonNode item(String bodyId, String id) {
    var page = api.get("/api/memory/cosmos/bodies/" + bodyId + "/items");
    assertThat(page.ok()).as(page.body()).isTrue();
    for (JsonNode n : page.json().path("items")) if (n.path("id").asText().equals(id)) return n;
    return null;
  }

  private static LlmResponse delegate(String agentKey, String task) {
    return new LlmResponse("", List.of(new ToolCall("call-" + agentKey, "delegate_to_specialist",
        Json.obj().put("agent_key", agentKey).put("task", task))), "tool_use", 10, 5, "m");
  }

  // ── The picture ──────────────────────────────────────────────────────────────────────────────

  @Test
  void theCosmosShowsTheWifeUnderJizzaAndHerAccountUnderKaiLinkedToHerAndTheConnector() {
    Map<String, JsonNode> bodies = bodies();
    JsonNode sun = bodies.values().stream().filter(b -> b.path("type").asText().equals("SUN"))
        .findFirst().orElseThrow();
    String kai = "AGENT:" + agentId("finance");

    assertThat(bodies.get("SUBJECT:" + priyaId).path("parentBodyId").asText())
        .isEqualTo(sun.path("bodyId").asText());
    assertThat(bodies.get("SUBJECT:" + accountId).path("parentBodyId").asText()).isEqualTo(kai);
    assertThat(bodies.get("SUBJECT:" + priyaId).path("memoryCount").asInt()).isEqualTo(4);
    assertThat(bodies).containsKeys(kai, "AGENT:" + agentId("health"), "AGENT:" + agentId("professional"));

    JsonNode sip = item("SUBJECT:" + priyaId, sipId);
    assertThat(sip.path("agentKey").asText()).isEqualTo("finance");
    assertThat(sip.path("tetherBodyIds").toString()).contains(kai);
    assertThat(item("SUBJECT:" + priyaId, allergyId).path("agentKey").asText()).isEqualTo("health");
  }

  // ── Read stamps from a real turn ─────────────────────────────────────────────────────────────

  @Test
  void whenKaiAnswersUsingHerPlanThePassportShowsOneReadByKaiForThatTurn() {
    model.scriptFor("Jizza",
        delegate("finance", "Should my wife increase her monthly SIP this year?"),
        LlmResponse.text("Kai suggests stepping Priya's SIP up gradually."));
    model.scriptFor("Kai", LlmResponse.text("Given her moderate risk tolerance, step it up 10%."));
    assertThat(api.post("/api/chat", Map.of("message", "Should my wife invest more this year?")).ok()).isTrue();

    var passport = api.get("/api/memory/cosmos/items/fact/" + sipId + "/passport");
    assertThat(passport.ok()).as(passport.body()).isTrue();
    List<JsonNode> reads = new java.util.ArrayList<>();
    for (JsonNode s : passport.json().path("stamps")) if (s.path("action").asText().equals("read")) reads.add(s);
    assertThat(reads).singleElement().satisfies(read -> {
      assertThat(read.path("actorLabel").asText()).isEqualTo("Kai");
      assertThat(read.path("contextKind").asText()).isEqualTo("chat_turn");
    });
    assertThat(passport.json().path("item").path("accessCount").asInt()).isEqualTo(1);
    assertThat(passport.json().path("origin").path("kind").asText()).isEqualTo("user");
  }

  // ── Sharing and restriction ──────────────────────────────────────────────────────────────────

  @Test
  void sharingAFactWithCatoReachesCatoButNotVitaAndUnsharingUndoesIt() {
    String cato = agentId("professional");
    var shared = api.post("/api/memory/" + sipId + "/share", Map.of("agentId", cato));
    assertThat(shared.ok()).as(shared.body()).isTrue();

    assertThat(preview("professional")).contains("Nifty");
    assertThat(preview("health")).doesNotContain("Nifty");
    assertThat(item("SUBJECT:" + priyaId, sipId).path("tetherBodyIds").toString()).contains("AGENT:" + cato);

    assertThat(api.delete("/api/memory/" + sipId + "/share/" + cato).ok()).isTrue();
    assertThat(preview("professional")).doesNotContain("Nifty");
  }

  @Test
  void aRestrictedFactIsLockedForAgentsAndRevealedOnlyAfterStepUp() {
    assertThat(api.post("/api/memory/" + sipId + "/restrict", Map.of()).ok()).isTrue();
    assertThat(preview("finance")).doesNotContain("Nifty");

    JsonNode node = item("SUBJECT:" + priyaId, sipId);
    assertThat(node.path("restricted").asBoolean()).isTrue();
    assertThat(node.path("label").isNull()).isTrue();
    assertThat(api.get("/api/memory/" + sipId).json().path("memory").path("content").isNull()).isTrue();
    assertThat(api.get("/api/memory/cosmos/items/fact/" + sipId + "/passport").json()
        .path("content").isNull()).isTrue();

    // A fresh session has no recent step-up.
    ApiClient later = signedInWithoutStepUp();
    var refused = later.get("/api/memory/cosmos/items/fact/" + sipId + "/reveal");
    assertThat(refused.status()).isEqualTo(403);
    assertThat(refused.problemType()).endsWith("step-up-required");
    assertThat(later.post("/api/memory/" + sipId + "/unrestrict", Map.of()).status()).isEqualTo(403);

    later.post("/api/auth/step-up", Map.of("password", PASSWORD));
    assertThat(later.get("/api/memory/cosmos/items/fact/" + sipId + "/reveal").json()
        .path("content").asText()).contains("Nifty 50");
  }

  @Test
  void movingAndForgettingNeedAFreshStepUpAndLeaveAnAuditTrailWithoutContent() {
    ApiClient later = signedInWithoutStepUp();
    assertThat(later.post("/api/memory/" + sipId + "/move", Map.of("domains", List.of("professional")))
        .status()).isEqualTo(403);
    assertThat(later.delete("/api/memory/" + allergyId).status()).isEqualTo(403);

    later.post("/api/auth/step-up", Map.of("password", PASSWORD));
    var moved = later.post("/api/memory/" + sipId + "/move", Map.of("domains", List.of("professional")));
    assertThat(moved.ok()).as(moved.body()).isTrue();
    assertThat(preview("finance")).doesNotContain("Nifty");
    assertThat(preview("professional")).contains("Nifty");
    // Nobody can be handed a fact parked in a domain no agent works in.
    assertThat(later.post("/api/memory/" + sipId + "/move", Map.of("domains", List.of("astrology")))
        .status()).isEqualTo(400);

    assertThat(later.delete("/api/memory/" + allergyId).ok()).isTrue();
    assertThat(later.get("/api/memory/cosmos/items/fact/" + allergyId + "/passport").status()).isEqualTo(404);
    assertThat(item("SUBJECT:" + priyaId, allergyId)).isNull();

    String activity = api.get("/api/activity?action=memory.moved").body()
        + api.get("/api/activity?action=memory.deleted").body();
    assertThat(activity).contains(sipId).contains(allergyId)
        .doesNotContain("Nifty").doesNotContain("penicillin");
  }

  // ── Notes ────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aNoteCanBeMovedOrPromotedButNeverShared() {
    String kai = agentId("finance");
    String vita = agentId("health");
    // What Kai saved for itself while working.
    String noteId = knowledge.upsert(UUID.fromString(kai), com.jizza.engine.api.knowledge.KnowledgeEntry
        .create(null, UUID.fromString(kai), "Card statement", "Closing balance 12,000 on the card",
            null, com.jizza.engine.api.enums.DataClassification.FINANCIAL)).id().toString();

    // A note is an agent's own; there is no sharing it.
    assertThat(api.post("/api/memory/" + noteId + "/share", Map.of("agentId", vita)).status()).isEqualTo(404);

    var moved = api.post("/api/agents/" + kai + "/memory/notes/" + noteId + "/move", Map.of("toAgentId", vita));
    assertThat(moved.ok()).as(moved.body()).isTrue();
    assertThat(api.get("/api/agents/" + vita + "/memory").json().path("notes").toString()).contains(noteId);
    assertThat(api.get("/api/agents/" + kai + "/memory").json().path("notes").toString()).doesNotContain(noteId);
    var hits = api.get("/api/memory/cosmos/search?q=" + URLEncoder.encode("Closing balance", StandardCharsets.UTF_8));
    assertThat(hits.json().toString()).contains(noteId).contains("AGENT:" + vita);

    var promoted = api.post("/api/agents/" + vita + "/memory/notes/" + noteId + "/promote",
        Map.of("category", "FINANCE", "removeNote", false));
    assertThat(promoted.ok()).as(promoted.body()).isTrue();
    String factId = promoted.json().path("memory").path("id").asText();
    var passport = api.get("/api/memory/cosmos/items/fact/" + factId + "/passport").json();
    assertThat(passport.path("origin").path("kind").asText()).isEqualTo("import");
    assertThat(passport.path("origin").path("ref").asText()).isEqualTo("note:" + noteId);
  }

  // ── Live updates ─────────────────────────────────────────────────────────────────────────────

  @Test
  void aNewMemoryIsPushedToAnOpenCosmosWithIdsOnly() throws Exception {
    var client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new org.springframework.messaging.converter.SimpleMessageConverter());
    var handshake = new WebSocketHttpHeaders();
    handshake.add("Cookie", api.cookieHeader());
    StompSession session = client.connectAsync("ws://localhost:" + port + "/ws/websocket", handshake,
        new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
    BlockingQueue<String> received = new LinkedBlockingQueue<>();
    session.subscribe("/user/queue/memory", new StompFrameHandler() {
      @Override public java.lang.reflect.Type getPayloadType(StompHeaders headers) { return byte[].class; }
      @Override public void handleFrame(StompHeaders headers, Object payload) {
        received.add(new String((byte[]) payload, StandardCharsets.UTF_8));
      }
    });
    Thread.sleep(200);

    String id = remember("PREFERENCE", "Priya prefers gold over equities", null);

    String frame = received.poll(2, TimeUnit.SECONDS);
    assertThat(frame).as("pushed within two seconds").isNotNull();
    assertThat(frame).contains("MEMORY_CREATED").contains(id).doesNotContain("gold");
    session.disconnect();
    client.stop();
  }

  // ── Isolation ────────────────────────────────────────────────────────────────────────────────

  @Test
  void anotherUserCannotSeeOrChangeAnyOfIt() {
    ApiClient stranger = new ApiClient(port);
    stranger.prime();
    String email = "stranger-" + UUID.randomUUID() + "@jizza.local";
    stranger.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    stranger.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    stranger.post("/api/auth/step-up", Map.of("password", PASSWORD));

    assertThat(stranger.get("/api/memory/cosmos").body()).doesNotContain(priyaId).doesNotContain(sipId);
    assertThat(stranger.get("/api/memory/cosmos/bodies/SUBJECT:" + priyaId + "/items").status()).isEqualTo(404);
    assertThat(stranger.get("/api/memory/cosmos/items/fact/" + sipId + "/passport").status()).isEqualTo(404);
    assertThat(stranger.get("/api/memory/cosmos/items/fact/" + sipId + "/reveal").status()).isEqualTo(404);
    assertThat(stranger.get("/api/memory/cosmos/search?q=Nifty").json()).isEmpty();
    assertThat(stranger.post("/api/memory/" + sipId + "/restrict", Map.of()).status()).isEqualTo(404);
    assertThat(stranger.post("/api/memory/" + sipId + "/move", Map.of("domains", List.of())).status())
        .isEqualTo(404);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  private String preview(String agentKey) {
    var preview = api.post("/api/memory/preview",
        Map.of("agentKey", agentKey, "request", "Should my wife invest more in her SIP?"));
    assertThat(preview.ok()).as(preview.body()).isTrue();
    return preview.json().path("context").asText();
  }

  /** The same user in a second session, which has not stepped up. */
  private ApiClient signedInWithoutStepUp() {
    ApiClient other = new ApiClient(port);
    other.prime();
    var login = other.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(login.ok()).as(login.body()).isTrue();
    return other;
  }
}
