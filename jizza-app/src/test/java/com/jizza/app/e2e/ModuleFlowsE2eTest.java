package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.util.Json;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * One end-to-end flow per module that had none: the connector builder, guardrails, conversations,
 * the notifications inbox, the workflow library, usage, memory transfer, agent editing and the
 * procedures example. Each flow goes through the HTTP API the client uses, top to bottom, with
 * the edge cases a first user is likely to hit.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class ModuleFlowsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;
  @Autowired private NotificationBus bus;
  @Autowired private AgentDirectory agents;

  private ApiClient api;
  private UUID userId;
  private StubService stub;

  @BeforeEach
  void setUp() {
    model.reset();
    api = new ApiClient(port);
    api.prime();
    String email = "flows-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok())
        .isTrue();
    var login = api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    userId = UUID.fromString(login.json().path("userId").asText());
    stepUp();
  }

  @AfterEach
  void tearDown() {
    if (stub != null) stub.close();
  }

  private void stepUp() {
    assertThat(api.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
  }

  private UUID agentId(String key) {
    return agents.byKey(userId, key).map(AgentDto::id).orElseThrow();
  }

  private static void ok(ApiClient.Result result) {
    assertThat(result.ok()).as("%s %s", result.status(), result.body()).isTrue();
  }

  // ── connector builder ────────────────────────────────────────────────────

  @Test
  void aServiceIsBuiltTestedPublishedAndRemoved() {
    stub = new StubService();
    var created = api.post("/api/services", Map.of("name", "Pet shop", "description", "Pets",
        "baseUrl", stub.apiBaseUrl(), "authScheme", "NO_AUTH"));
    ok(created);
    String id = created.json().path("id").asText();

    Map<String, Object> apiInput = new HashMap<>();
    apiInput.put("name", "list_pets");
    apiInput.put("label", "List pets");
    apiInput.put("httpMethod", "GET");
    apiInput.put("pathTemplate", "/pets");
    var listPets = api.post("/api/services/" + id + "/apis", apiInput);
    ok(listPets);
    String apiId = listPets.json().path("id").asText();

    var action = api.post("/api/services/" + id + "/actions",
        Map.of("name", "list_pets", "label", "List pets", "apiId", apiId));
    ok(action);
    String actionId = action.json().path("id").asText();

    var secret = api.post("/api/services/" + id + "/variables",
        Map.of("name", "shop_key", "value", "very-secret-value", "secret", true));
    ok(secret);
    assertThat(secret.body()).doesNotContain("very-secret-value");

    // The stub wants a token this service was never given: the test reports why, as a result
    // the editor can show, rather than failing the request.
    var tested = api.post("/api/services/" + id + "/actions/" + actionId + "/test", Map.of());
    ok(tested);
    assertThat(tested.json().path("ok").asBoolean()).isFalse();
    assertThat(tested.json().path("error").asText()).contains("401");
    assertThat(stub.callsTo("/v1/pets")).isEqualTo(1);

    var detail = api.get("/api/services/" + id);
    ok(detail);
    assertThat(detail.json().path("apis")).hasSize(1);
    assertThat(detail.json().path("actions")).hasSize(1);
    assertThat(detail.body()).doesNotContain("very-secret-value");

    var history = api.get("/api/services/" + id + "/history");
    ok(history);
    assertThat(history.json().size()).isGreaterThanOrEqualTo(1);

    ok(api.post("/api/services/" + id + "/publish", null));
    ok(api.post("/api/services/" + id + "/unpublish", null));

    // An action pointing at an API from somewhere else is refused, not saved half-made.
    var stray = api.post("/api/services/" + id + "/actions",
        Map.of("name", "stray", "label", "Stray", "apiId", UUID.randomUUID().toString()));
    assertThat(stray.status()).isBetween(400, 404);

    ok(api.delete("/api/services/" + id));
    assertThat(api.get("/api/services/" + id).status()).isEqualTo(404);
  }

  @Test
  void aServiceNeedsANameAndAUsableAddress() {
    var nameless = api.post("/api/services", Map.of("baseUrl", "https://api.example.com"));
    assertThat(nameless.status()).isEqualTo(400);
    var nowhere = api.post("/api/services", Map.of("name", "Nowhere", "baseUrl", "not a url"));
    assertThat(nowhere.status()).isEqualTo(400);
    var tooLong = api.post("/api/services", Map.of("name", "x".repeat(5_000),
        "baseUrl", "https://api.example.com"));
    assertThat(tooLong.status()).isEqualTo(400);
  }

  // ── guardrails ───────────────────────────────────────────────────────────

  @Test
  void aRuleIsSetSimulatedAndRemoved() {
    var overview = api.get("/api/guardrails");
    ok(overview);
    assertThat(overview.json().path("agents").size()).isGreaterThan(0);

    String finance = agentId("finance").toString();
    var rule = api.put("/api/guardrails/rules", Map.of("agentId", finance,
        "toolRef", "pay_supplier", "rule", "DENY", "precedence", 50));
    ok(rule);
    String ruleId = rule.json().path("id").asText();

    var simulated = api.post("/api/guardrails/simulate",
        Map.of("agentId", finance, "toolRef", "pay_supplier"));
    ok(simulated);
    assertThat(simulated.body()).contains("DENY");

    ok(api.delete("/api/guardrails/rules/" + ruleId));
    var after = api.post("/api/guardrails/simulate",
        Map.of("agentId", finance, "toolRef", "pay_supplier"));
    ok(after);
    assertThat(after.body()).doesNotContain(ruleId);
  }

  @Test
  void quietHoursRejectANonsenseTimeZone() {
    var bad = api.put("/api/guardrails/quiet-hours",
        Map.of("start", "22:00", "end", "07:00", "timeZone", "Mars/Olympus_Mons"));
    assertThat(bad.status()).isEqualTo(400);
    var good = api.put("/api/guardrails/quiet-hours",
        Map.of("start", "22:00", "end", "07:00", "timeZone", "Asia/Singapore"));
    ok(good);
  }

  // ── conversations ────────────────────────────────────────────────────────

  @Test
  void aConversationIsRenamedPinnedArchivedExportedAndDeleted() {
    model.scriptFor("Jizza", LlmResponse.text("Hello, I am here."));
    var reply = api.post("/api/chat", Map.of("message", "Hello there"));
    ok(reply);
    String id = reply.json().path("conversationId").asText();

    ok(api.patch("/api/conversations/" + id, Map.of("title", "Greetings", "pinned", true)));
    var list = api.get("/api/conversations");
    assertThat(list.body()).contains("Greetings");

    var messages = api.get("/api/conversations/" + id + "/messages");
    ok(messages);
    assertThat(messages.body()).contains("Hello there").contains("Hello, I am here.");

    var markdown = api.get("/api/conversations/" + id + "/export?format=md");
    ok(markdown);
    assertThat(markdown.body()).contains("Hello there");
    var unknownFormat = api.get("/api/conversations/" + id + "/export?format=exe");
    assertThat(unknownFormat.status()).isEqualTo(400);

    ok(api.patch("/api/conversations/" + id, Map.of("archived", true)));
    assertThat(api.get("/api/conversations").body()).doesNotContain(id);
    assertThat(api.get("/api/conversations?archived=true").body()).contains(id);

    var tooLong = api.patch("/api/conversations/" + id, Map.of("title", "x".repeat(500)));
    assertThat(tooLong.status()).isEqualTo(400);

    ok(api.delete("/api/conversations/" + id));
    assertThat(api.get("/api/conversations/" + id).status()).isEqualTo(404);
  }

  @Test
  void anEmptyOrBlankChatMessageIsRefused() {
    assertThat(api.post("/api/chat", Map.of("message", "")).status()).isEqualTo(400);
    assertThat(api.post("/api/chat", Map.of("message", "   ")).status()).isEqualTo(400);
    assertThat(api.post("/api/chat/turns", Map.of()).status()).isEqualTo(400);
  }

  // ── notifications inbox ──────────────────────────────────────────────────

  @Test
  void anUpdateIsReadArchivedSnoozedAndCounted() {
    UUID id = bus.raise(NotificationKind.INFO, agentId("finance"),
        new NotificationContext(userId, "Your statement is ready", Json.obj(), null, null, null,
            null)).id();

    var summary = api.get("/api/notifications/summary");
    ok(summary);

    ok(api.post("/api/notifications/" + id + "/read", null));
    ok(api.post("/api/notifications/" + id + "/unread", null));
    ok(api.post("/api/notifications/read-all", null));

    var snoozed = api.post("/api/notifications/" + id + "/snooze",
        Map.of("until", Instant.now().plusSeconds(3600).toString()));
    ok(snoozed);
    var pastSnooze = api.post("/api/notifications/" + id + "/snooze",
        Map.of("until", "2000-01-01T00:00:00Z"));
    assertThat(pastSnooze.status()).as(pastSnooze.body()).isLessThan(500);

    ok(api.post("/api/notifications/" + id + "/archive", null));
    ok(api.post("/api/notifications/" + id + "/unarchive", null));
    ok(api.get("/api/notifications/" + id));
  }

  @Test
  void aBlockingRequestCannotBeArchivedWhileItWaits() {
    UUID id = bus.raise(NotificationKind.PERMISSION_REQUEST, agentId("finance"),
        new NotificationContext(userId, "Pay the supplier?", Json.obj().put("tool", "pay"),
            null, null, null, null)).id();
    var archived = api.post("/api/notifications/" + id + "/archive", null);
    assertThat(archived.status()).isBetween(400, 499);

    var answered = api.post("/api/notifications/" + id + "/answer",
        Map.of("kind", "DENY", "feedback", "Not this month"));
    ok(answered);
    var again = api.post("/api/notifications/" + id + "/answer", Map.of("kind", "APPROVE"));
    assertThat(again.status()).as("answering twice").isBetween(400, 499);
  }

  // ── workflow library ─────────────────────────────────────────────────────

  @Test
  void aWorkflowIsDescribedForReuseListedAndForgotten() {
    JsonNode graph = Json.read("""
        {"nodes":[{"id":"start","type":"TRIGGER"},{"id":"end","type":"END"}],
         "edges":[{"from":"start","to":"end"}]}""");
    var saved = api.post("/api/workflows", Map.of("name", "Morning digest", "graph", graph));
    ok(saved);
    String id = saved.json().path("id").asText();

    var described = api.put("/api/workflows/" + id + "/reuse", Map.of(
        "whenToUse", "When I ask for my morning digest",
        "examples", List.of("morning digest please")));
    ok(described);
    assertThat(api.get("/api/workflows/library").body()).contains(id);

    ok(api.get("/api/workflows/" + id + "/versions"));
    ok(api.delete("/api/workflows/" + id + "/reuse"));
    assertThat(api.get("/api/workflows/library").body()).doesNotContain(id);

    var nameless = api.post("/api/workflows", Map.of("name", "", "graph", graph));
    assertThat(nameless.status()).isEqualTo(400);
    var graphless = api.post("/api/workflows", Map.of("name", "No graph"));
    assertThat(graphless.status()).isEqualTo(400);
  }

  // ── usage, memory transfer ───────────────────────────────────────────────

  @Test
  void aNewAccountHasEmptyButWorkingUsage() {
    ok(api.get("/api/usage/summary"));
    var entries = api.get("/api/usage/entries");
    ok(entries);
    assertThat(entries.json().isArray()).isTrue();
    ok(api.get("/api/usage/explore?groupBy=MODEL"));
  }

  @Test
  void memoryPastedFromAnotherAssistantIsImportedAndExported() {
    var prompt = api.get("/api/memory/import/prompt");
    ok(prompt);
    var imported = api.post("/api/memory/import", Map.of(
        "text", "I live in Singapore.\nI prefer emails in the morning.", "provider", "Other"));
    ok(imported);

    var exported = api.get("/api/memory/export");
    ok(exported);

    var empty = api.post("/api/memory/import", Map.of("text", ""));
    assertThat(empty.status()).isEqualTo(400);
  }

  // ── agents ───────────────────────────────────────────────────────────────

  @Test
  void anAgentIsRenamedRevisedAndTried() {
    String cato = agentId("professional").toString();
    var renamed = api.patch("/api/agents/" + cato, Map.of("name", "Cato the Elder"));
    ok(renamed);
    assertThat(api.get("/api/agents/" + cato).body()).contains("Cato the Elder");

    ok(api.get("/api/agents/" + cato + "/revisions"));
    ok(api.get("/api/agents/" + cato + "/tools"));
    ok(api.get("/api/agents/" + cato + "/modes"));
    ok(api.get("/api/agents/" + cato + "/spend"));

    model.scriptFor("Cato the Elder", LlmResponse.text("Ready when you are."));
    model.fallback = messages -> LlmResponse.text("Ready when you are.");
    var tried = api.post("/api/agents/" + cato + "/try", Map.of("message", "Are you there?"));
    ok(tried);

    var blank = api.patch("/api/agents/" + cato, Map.of("name", "   "));
    assertThat(blank.status()).isEqualTo(400);
  }

  // ── procedures ───────────────────────────────────────────────────────────

  @Test
  void theProcedureExampleCanBeAddedReviewedAndApproved() {
    var example = api.post("/api/procedures/example", null);
    ok(example);

    var procedures = api.get("/api/procedures");
    ok(procedures);
    assertThat(procedures.json().size()).isGreaterThanOrEqualTo(1);
    String id = procedures.json().get(0).path("workflow").path("id").asText();

    ok(api.get("/api/procedures/" + id + "/history"));
    var approved = api.post("/api/procedures/" + id + "/approve", null);
    ok(approved);
    var twice = api.post("/api/procedures/" + id + "/approve", null);
    assertThat(twice.status()).as(twice.body()).isLessThan(500);

    var tasks = api.get("/api/tasks");
    assertThat(tasks.body()).containsIgnoringCase("check-in");
  }
}
