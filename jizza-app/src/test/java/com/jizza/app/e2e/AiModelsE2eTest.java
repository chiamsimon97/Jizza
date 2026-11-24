package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.llm.LlmResponse;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A choice of models (Phase D): connecting a provider with the person's own key, picking one of
 * its models for a chat and for an agent, keeping an earlier answer, and the key never coming back.
 * The provider is a local stand-in that lists models the way OpenAI does.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class AiModelsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final String KEY = "sk-test-own-key-1234567890abcd";

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;
  @Autowired private JdbcTemplate jdbc;

  private ApiClient api;
  private HttpServer provider;
  private final List<String> seenKeys = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    api = new ApiClient(port);
    api.prime();
    model.reset();
    String email = "models-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));

    provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    provider.createContext("/v1/models", exchange -> {
      seenKeys.add(exchange.getRequestHeaders().getFirst("Authorization"));
      boolean ok = ("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] body = (ok
          ? "{\"data\":[{\"id\":\"gpt-5.1\"},{\"id\":\"gpt-5-mini\"},{\"id\":\"text-embedding-3-small\"}]}"
          : "{\"error\":{\"message\":\"Incorrect API key\"}}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("content-type", "application/json");
      exchange.sendResponseHeaders(ok ? 200 : 401, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    provider.start();
  }

  @AfterEach
  void tearDown() {
    provider.stop(0);
  }

  private String base() {
    return "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
  }

  private ApiClient.Result connect(String key) {
    api.post("/api/auth/step-up", Map.of("password", PASSWORD));
    return api.put("/api/ai-models/providers/openai", Map.of("baseUrl", base(), "apiKey", key));
  }

  @Test
  void aWrongKeyIsCaughtAtOnceAndNothingIsSaved() {
    var refused = connect("sk-wrong-key-000000000000000");
    assertThat(refused.status()).as(refused.body()).isEqualTo(400);
    assertThat(refused.body()).contains("key was not accepted");
    assertThat(api.get("/api/ai-models").json().path("connected")).isEmpty();
  }

  @Test
  void connectingListsTheChatModelsAndNeverGivesTheKeyBack() {
    var connected = connect(KEY);
    assertThat(connected.ok()).as(connected.body()).isTrue();
    assertThat(connected.body()).doesNotContain(KEY);
    assertThat(connected.json().path("keyHint").asText()).isEqualTo("••••abcd");
    assertThat(connected.json().path("models")).extracting(m -> m.path("id").asText())
        .containsExactlyInAnyOrder("gpt-5.1", "gpt-5-mini");

    var overview = api.get("/api/ai-models");
    assertThat(overview.body()).doesNotContain(KEY);
    assertThat(overview.json().path("models")).extracting(m -> m.path("ref").asText())
        .contains("openai:gpt-5.1", "openai:gpt-5-mini");
    // Stored encrypted: the plain key is nowhere in the table.
    String stored = jdbc.queryForObject("select encode(api_key, 'escape') from llm_providers limit 1",
        String.class);
    assertThat(stored).doesNotContain(KEY);

    // Hiding a model takes it out of the picker, and a refresh keeps it hidden.
    var hidden = api.patch("/api/ai-models/providers/openai/model?id=gpt-5-mini", Map.of("hidden", true));
    assertThat(hidden.ok()).as(hidden.body()).isTrue();
    assertThat(api.post("/api/ai-models/providers/openai/refresh", Map.of()).ok()).isTrue();
    assertThat(api.get("/api/ai-models/models").json()).extracting(m -> m.path("ref").asText())
        .doesNotContain("openai:gpt-5-mini");
  }

  @Test
  void aChatSetToTheirModelAnswersWithItAndSaysSo() {
    assertThat(connect(KEY).ok()).isTrue();
    String chat = api.post("/api/conversations", Map.of()).json().path("id").asText();

    var unknown = api.put("/api/conversations/" + chat + "/choice", Map.of("model", "openai:nope"));
    assertThat(unknown.status()).isEqualTo(400);
    var set = api.put("/api/conversations/" + chat + "/choice",
        Map.of("model", "openai:gpt-5.1", "depth", "deep"));
    assertThat(set.ok()).as(set.body()).isTrue();
    assertThat(api.get("/api/conversations/" + chat + "/choice").json().path("depth").asText()).isEqualTo("deep");

    model.script(LlmResponse.text("First answer."));
    var reply = api.post("/api/chat", Map.of("message", "Plan my week", "conversationId", chat));
    assertThat(reply.ok()).as(reply.body()).isTrue();

    JsonNode answer = lastReply(chat);
    assertThat(answer.path("metadata").path("model").path("ref").asText()).isEqualTo("openai:gpt-5.1");
    assertThat(answer.path("metadata").path("model").path("ownKey").asBoolean()).isTrue();
    // Paid with their key, so it does not count towards the platform's ceiling.
    assertThat(jdbc.queryForObject("select count(*) from llm_usage where model = 'openai:gpt-5.1' and paid_by = 'own'",
        Integer.class)).isPositive();

    // "Ask another model": the same question again, the first answer kept as an earlier version.
    model.script(LlmResponse.text("Second answer."));
    String asked = answer.path("id").asText();
    api.post("/api/conversations/" + chat + "/messages/" + userMessageBefore(chat) + "/rewind", Map.of());
    var again = api.post("/api/chat/turns", Map.of("message", "Plan my week", "conversationId", chat,
        "clientMessageId", UUID.randomUUID().toString(),
        "context", Map.of("model", "openai:gpt-5-mini", "versions",
            List.of(Map.of("text", "First answer.", "model", Map.of("ref", "openai:gpt-5.1", "label", "GPT-5.1"))))));
    assertThat(again.status()).as(again.body()).isEqualTo(202);
    awaitJob(again.json().path("jobId").asText());
    JsonNode second = lastReply(chat);
    assertThat(second.path("content").asText()).isEqualTo("Second answer.");
    assertThat(second.path("metadata").path("versions").get(0).path("model").path("label").asText()).isEqualTo("GPT-5.1");

    // Keeping the first: the chat now continues from it.
    var kept = api.post("/api/conversations/" + chat + "/messages/" + second.path("id").asText() + "/keep",
        Map.of("index", 0));
    assertThat(kept.status()).as(kept.body()).isEqualTo(204);
    JsonNode after = lastReply(chat);
    assertThat(after.path("content").asText()).isEqualTo("First answer.");
    assertThat(after.path("metadata").path("versions").get(0).path("text").asText()).isEqualTo("Second answer.");
    assertThat(asked).isNotBlank();
  }

  @Test
  void anAgentCanBeGivenOneOfTheirModelsByDefault() {
    assertThat(connect(KEY).ok()).isTrue();
    JsonNode kai = null;
    for (JsonNode a : api.get("/api/agents").json()) if ("finance".equals(a.path("key").asText())) kai = a;
    assertThat(kai).isNotNull();
    String path = "/api/agents/" + kai.path("id").asText() + "/model";

    assertThat(api.put(path, Map.of("model", "openai:nope")).status()).isEqualTo(400);
    var set = api.put(path, Map.of("model", "openai:gpt-5.1"));
    assertThat(set.ok()).as(set.body()).isTrue();
    assertThat(api.get(path).json().path("label").asText()).isEqualTo("GPT-5.1");
    assertThat(api.put(path, Map.of("model", "auto")).json().path("model").isNull()).isTrue();
  }

  @Test
  void removingAProviderTakesItsModelsAway() {
    assertThat(connect(KEY).ok()).isTrue();
    assertThat(api.delete("/api/ai-models/providers/openai").status()).isEqualTo(204);
    assertThat(api.get("/api/ai-models/models").json()).extracting(m -> m.path("ref").asText())
        .noneMatch(ref -> ref.startsWith("openai:"));
  }

  private void awaitJob(String jobId) {
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline) {
      if ("completed".equals(api.get("/api/chat/jobs/" + jobId).json().path("status").asText())) return;
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError("job " + jobId + " never completed");
  }

  private JsonNode lastReply(String chat) {
    JsonNode last = null;
    for (JsonNode m : api.get("/api/conversations/" + chat + "/messages").json()) {
      if ("assistant".equalsIgnoreCase(m.path("role").asText())) last = m;
    }
    assertThat(last).isNotNull();
    return last;
  }

  private String userMessageBefore(String chat) {
    String last = null;
    for (JsonNode m : api.get("/api/conversations/" + chat + "/messages").json()) {
      if ("user".equalsIgnoreCase(m.path("role").asText())) last = m.path("id").asText();
    }
    return last;
  }
}
