package com.jizza.engine.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Retries, prompt caching and thinking-block round trips, against a local server that plays the
 * provider, so what is tested is the real HTTP exchange rather than a mock of it.
 */
class AnthropicLlmClientResilienceTest {

  /** One scripted reply: a status, optional headers, and a body. */
  record Reply(int status, Map<String, String> headers, String body, String contentType) {
    static Reply json(String body) { return new Reply(200, Map.of(), body, "application/json"); }
    static Reply sse(String body) { return new Reply(200, Map.of(), body, "text/event-stream"); }
    static Reply status(int status, Map<String, String> headers) {
      return new Reply(status, headers,
          "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}",
          "application/json");
    }
  }

  private HttpServer server;
  private final Deque<Reply> replies = new ArrayDeque<>();
  private final List<JsonNode> requests = new ArrayList<>();
  private final List<Duration> pauses = new ArrayList<>();
  private AgentProperties props;
  private AnthropicLlmClient client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/messages", exchange -> {
      requests.add(Json.read(new String(exchange.getRequestBody().readAllBytes(),
          StandardCharsets.UTF_8)));
      Reply reply = replies.isEmpty() ? Reply.status(500, Map.of()) : replies.poll();
      reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
      exchange.getResponseHeaders().add("content-type", reply.contentType());
      byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(reply.status(), bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();

    props = new AgentProperties();
    props.getAnthropic().setApiKey("test-key");
    props.getAnthropic().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    props.getAnthropic().getRetry().setInitialBackoff(Duration.ofMillis(100));
    client = new AnthropicLlmClient(props, pauses::add);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static String message(String text) {
    return "{\"model\":\"m\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\""
        + text + "\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":3}}";
  }

  // ── Retries ───────────────────────────────────────────────────────────────────

  @Test
  void anOverloadedProviderIsTriedAgainAndTheSecondAnswerIsUsed() {
    replies.add(Reply.status(529, Map.of()));
    replies.add(Reply.json(message("hello")));

    LlmResponse response = client.complete("m", List.of(Msg.user("hi")), List.of());

    assertThat(response.text()).isEqualTo("hello");
    assertThat(requests).hasSize(2);
    assertThat(pauses).hasSize(1);
  }

  @Test
  void theProvidersRetryAfterIsHonoured() {
    replies.add(Reply.status(429, Map.of("retry-after", "3")));
    replies.add(Reply.json(message("ok")));

    client.complete("m", List.of(Msg.user("hi")), List.of());

    assertThat(pauses).containsExactly(Duration.ofSeconds(3));
  }

  @Test
  void aRetryAfterLongerThanTheLimitEndsTheCallInsteadOfHoldingTheTurn() {
    props.getAnthropic().getRetry().setMaxRetryAfter(Duration.ofSeconds(10));
    replies.add(Reply.status(429, Map.of("retry-after", "120")));

    assertThatThrownBy(() -> client.complete("m", List.of(Msg.user("hi")), List.of()))
        .isInstanceOf(JizzaException.class);
    assertThat(requests).hasSize(1);
    assertThat(pauses).isEmpty();
  }

  @Test
  void aRequestTheProviderRejectsIsNeverRepeated() {
    replies.add(Reply.status(400, Map.of()));

    assertThatThrownBy(() -> client.complete("m", List.of(Msg.user("hi")), List.of()))
        .isInstanceOf(JizzaException.class)
        .hasMessageContaining("400");
    assertThat(requests).hasSize(1);
  }

  @Test
  void retryingStopsAfterTheConfiguredNumberOfAttempts() {
    props.getAnthropic().getRetry().setMaxAttempts(3);
    for (int i = 0; i < 5; i++) replies.add(Reply.status(503, Map.of()));

    assertThatThrownBy(() -> client.complete("m", List.of(Msg.user("hi")), List.of()))
        .isInstanceOf(JizzaException.class);
    assertThat(requests).hasSize(3);
    assertThat(pauses).hasSize(2);
  }

  @Test
  void aStreamThatFailsBeforeAnyTextIsTriedAgain() {
    replies.add(Reply.sse("data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\","
        + "\"message\":\"Overloaded\"}}\n"));
    replies.add(Reply.sse(textStream("Hello there")));
    List<String> seen = new ArrayList<>();

    LlmResponse response = client.stream("m", List.of(Msg.user("hi")), List.of(), seen::add);

    assertThat(response.text()).isEqualTo("Hello there");
    assertThat(String.join("", seen)).isEqualTo("Hello there");
    assertThat(requests).hasSize(2);
  }

  @Test
  void aStreamRefusedWithAnOverloadStatusIsTriedAgain() {
    replies.add(Reply.status(529, Map.of("retry-after", "0")));
    replies.add(Reply.sse(textStream("Back again")));

    LlmResponse response = client.stream("m", List.of(Msg.user("hi")), List.of(), t -> { });

    assertThat(response.text()).isEqualTo("Back again");
    assertThat(requests).hasSize(2);
    assertThat(pauses).containsExactly(Duration.ZERO);
  }

  @Test
  void aStreamThatFailsAfterTheUserSawTextIsNotRepeated() {
    replies.add(Reply.sse(String.join("\n",
        "data: {\"type\":\"message_start\",\"message\":{\"model\":\"m\",\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}",
        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Half a \"}}",
        "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}",
        "")));
    replies.add(Reply.sse(textStream("Should never be sent")));
    List<String> seen = new ArrayList<>();

    assertThatThrownBy(() -> client.stream("m", List.of(Msg.user("hi")), List.of(), seen::add))
        .isInstanceOf(JizzaException.class);
    assertThat(seen).containsExactly("Half a ");
    assertThat(requests).hasSize(1);
  }

  @Test
  void backoffGrowsAndStaysUnderItsCap() {
    AgentProperties.Retry retry = new AgentProperties.Retry();
    retry.setInitialBackoff(Duration.ofSeconds(1));
    retry.setMaxBackoff(Duration.ofSeconds(8));
    for (int attempt = 1; attempt <= 10; attempt++) {
      Duration wait = AnthropicLlmClient.backoff(retry, attempt);
      long ceiling = Math.min(1000L << (attempt - 1), 8000L);
      assertThat(wait.toMillis()).isBetween(ceiling / 2, ceiling);
    }
  }

  @Test
  void retryAfterIsReadInSecondsAndIgnoredWhenUnreadable() {
    HttpHeaders headers = new HttpHeaders();
    headers.add("retry-after", "1.5");
    assertThat(AnthropicLlmClient.retryAfter(headers)).isEqualTo(Duration.ofMillis(1500));
    headers.set("retry-after", "Wed, 21 Oct 2026 07:28:00 GMT");
    assertThat(AnthropicLlmClient.retryAfter(headers)).isNull();
    assertThat(AnthropicLlmClient.retryAfter(new HttpHeaders())).isNull();
  }

  @Test
  void onlyPassingFailuresAreRetryable() {
    assertThat(client.rejected(HttpStatus.valueOf(429), new HttpHeaders(), "", null))
        .isInstanceOf(AnthropicLlmClient.Transient.class);
    assertThat(client.rejected(org.springframework.http.HttpStatusCode.valueOf(529),
        new HttpHeaders(), "", null)).isInstanceOf(AnthropicLlmClient.Transient.class);
    assertThat(client.rejected(HttpStatus.valueOf(401), new HttpHeaders(), "", null))
        .isInstanceOf(JizzaException.class);
  }

  // ── Prompt caching ────────────────────────────────────────────────────────────

  @Test
  void theToolsAndTheAgentsOwnInstructionsAreMarkedForTheCacheButNotWhatIsRemembered() {
    var body = client.buildRequest("m",
        List.of(Msg.system("You are Kai."), Msg.system("Remembered: likes index funds"),
            Msg.user("how are my savings?")),
        List.of(tool("zeta_tool"), tool("alpha_tool")));

    assertThat(body.path("system").get(0).path("cache_control").path("type").asText())
        .isEqualTo("ephemeral");
    assertThat(body.path("system").get(1).has("cache_control")).isFalse();
    // Tools render before the system prompt, so one cache point covers both; a stable order keeps
    // the prefix identical between calls.
    assertThat(body.path("tools").get(0).path("name").asText()).isEqualTo("alpha_tool");
    assertThat(body.path("tools").get(1).has("cache_control")).isFalse();
    // A one-off question is not cached: nothing would read it back.
    assertThat(body.has("cache_control")).isFalse();
  }

  @Test
  void insideAToolLoopTheConversationSoFarIsCachedToo() {
    var call = new com.jizza.engine.api.tool.ToolCall("tu_1", "alpha_tool", Json.obj());
    var body = client.buildRequest("m", List.of(Msg.system("You are Kai."), Msg.user("check"),
        Msg.assistantToolCalls("", List.of(call)),
        Msg.toolResults(List.of(new Msg.ToolResultRef("tu_1", ToolResult.ok("42"))))),
        List.of(tool("alpha_tool")));

    assertThat(body.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
  }

  @Test
  void withNoSystemPromptTheLastToolCarriesTheCachePoint() {
    var body = client.buildRequest("m", List.of(Msg.user("hi")),
        List.of(tool("b_tool"), tool("a_tool")));

    assertThat(body.path("tools").get(1).path("name").asText()).isEqualTo("b_tool");
    assertThat(body.path("tools").get(1).path("cache_control").path("type").asText())
        .isEqualTo("ephemeral");
  }

  @Test
  void cachedTokensAreCountedAsInputAndReportedSeparately() {
    replies.add(Reply.json("{\"model\":\"m\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":"
        + "\"text\",\"text\":\"hi\"}],\"usage\":{\"input_tokens\":50,\"output_tokens\":5,"
        + "\"cache_read_input_tokens\":3000,\"cache_creation_input_tokens\":200}}"));

    LlmResponse response = client.complete("m", List.of(Msg.user("hi")), List.of());

    assertThat(response.inputTokens()).isEqualTo(3250);
    assertThat(response.cacheReadTokens()).isEqualTo(3000);
    assertThat(response.cacheWriteTokens()).isEqualTo(200);
    assertThat(response.uncachedInputTokens()).isEqualTo(50);
  }

  // ── Thinking blocks ───────────────────────────────────────────────────────────

  @Test
  void aStreamedTurnKeepsItsThinkingAndItIsSentBackUnchangedOnTheNextRound() {
    replies.add(Reply.sse(String.join("\n",
        "data: {\"type\":\"message_start\",\"message\":{\"model\":\"m\",\"usage\":{\"input_tokens\":20,\"output_tokens\":1,\"cache_read_input_tokens\":100}}}",
        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}",
        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Need the balance.\"}}",
        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"c2lnbmF0dXJlLXNrLWFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6\"}}",
        "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu_9\",\"name\":\"alpha_tool\",\"input\":{}}}",
        "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"account\\\":\\\"savings\\\"}\"}}",
        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":40}}",
        "")));
    replies.add(Reply.json(message("Your savings are fine.")));

    LlmResponse first = client.stream("m", List.of(Msg.user("savings?")),
        List.of(tool("alpha_tool")), t -> { });

    assertThat(first.inputTokens()).isEqualTo(120);
    assertThat(first.providerContent()).hasSize(2);
    assertThat(first.providerContent().get(0).path("type").asText()).isEqualTo("thinking");
    assertThat(first.toolCalls().get(0).args().path("account").asText()).isEqualTo("savings");

    client.complete("m", List.of(Msg.user("savings?"), Msg.assistantTurn(first),
        Msg.toolResults(List.of(new Msg.ToolResultRef("tu_9", ToolResult.ok("1200"))))),
        List.of(tool("alpha_tool")));

    JsonNode replayed = requests.get(1).path("messages").get(1).path("content");
    assertThat(replayed.get(0).path("type").asText()).isEqualTo("thinking");
    assertThat(replayed.get(0).path("thinking").asText()).isEqualTo("Need the balance.");
    assertThat(replayed.get(0).path("signature").asText())
        .isEqualTo("c2lnbmF0dXJlLXNrLWFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6");
    assertThat(replayed.get(1).path("type").asText()).isEqualTo("tool_use");
    assertThat(replayed.get(1).path("input").path("account").asText()).isEqualTo("savings");
  }

  @Test
  void theSecretCheckStillCatchesCredentialsWhenThinkingIsPresent() {
    var thinking = Json.obj().put("type", "thinking").put("thinking", "")
        .put("signature", "opaque");
    var turn = new LlmResponse("", List.of(), "end_turn", 1, 1, "m", 0, 0, List.of(thinking));
    var leaky = client.buildRequest("m", List.of(Msg.assistantTurn(turn),
        Msg.user("my key is sk-abcdefghijklmnopqrstuvwxyz123456")), List.of());

    assertThatThrownBy(() -> client.assertNoSecrets(leaky)).isInstanceOf(JizzaException.class);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private static ToolSpec tool(String name) {
    return new ToolSpec(name, "does " + name, JsonSchemas.emptyObject());
  }

  private static String textStream(String text) {
    return String.join("\n",
        "data: {\"type\":\"message_start\",\"message\":{\"model\":\"m\",\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}",
        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}",
        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":4}}",
        "data: {\"type\":\"message_stop\"}",
        "");
  }
}
