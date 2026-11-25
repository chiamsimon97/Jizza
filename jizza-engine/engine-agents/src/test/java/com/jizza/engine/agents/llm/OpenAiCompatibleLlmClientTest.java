package com.jizza.engine.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.llm.LlmCallContext;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.ModelCatalog;
import com.jizza.engine.api.llm.ModelRefs;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolCall;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The OpenAI-compatible adapter against a local stand-in for the provider. */
class OpenAiCompatibleLlmClientTest {

  private record Reply(int status, String body, String type) {}

  private HttpServer server;
  private final Deque<Reply> replies = new ArrayDeque<>();
  private final List<JsonNode> requests = new ArrayList<>();
  private final List<String> auth = new ArrayList<>();
  private final List<Duration> pauses = new ArrayList<>();
  private AgentProperties props;
  private String base;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      requests.add(Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
      Reply reply = replies.isEmpty() ? new Reply(500, "{}", "application/json") : replies.poll();
      exchange.getResponseHeaders().add("content-type", reply.type());
      byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(reply.status(), bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    props = new AgentProperties();
    props.getAnthropic().getRetry().setInitialBackoff(Duration.ofMillis(10));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private OpenAiCompatibleLlmClient client(String provider, ModelCatalog.ModelInfo info) {
    return new OpenAiCompatibleLlmClient(provider, base, "sk-own-key", props, () -> null,
        id -> info, pauses::add);
  }

  private static ModelCatalog.ModelInfo info(boolean tools) {
    return new ModelCatalog.ModelInfo("openai:m", "openai", "M", null, true, tools, true, true,
        null, null, null);
  }

  private static final ToolSpec LOOKUP = new ToolSpec("lookup", "Find a thing",
      Json.obj().put("type", "object"));

  @Test
  void translatesMessagesAndToolsToTheChatCompletionsFormat() {
    var client = client(ModelRefs.OPENAI, info(true));
    var call = new ToolCall("call_1", "lookup", Json.obj().put("q", "x"));
    var body = client.buildRequest("gpt-5.1", List.of(
        Msg.system("You are Jizza."), Msg.system("Remembered: likes tea."),
        Msg.user("find x"), Msg.assistantToolCalls("Looking.", List.of(call)),
        Msg.toolResults(List.of(new Msg.ToolResultRef("call_1", ToolResult.ok(Json.obj().put("found", true)))))),
        List.of(LOOKUP, new ToolSpec(ToolSpec.WEB_SEARCH, "search", Json.obj())), false);

    assertThat(body.path("max_completion_tokens").asInt()).isPositive();
    JsonNode messages = body.path("messages");
    assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
    assertThat(messages.get(0).path("content").asText()).isEqualTo("You are Jizza.\n\nRemembered: likes tea.");
    assertThat(messages.get(1).path("content").asText()).isEqualTo("find x");
    assertThat(messages.get(2).path("tool_calls").get(0).path("function").path("name").asText()).isEqualTo("lookup");
    assertThat(messages.get(2).path("tool_calls").get(0).path("function").path("arguments").asText())
        .isEqualTo("{\"q\":\"x\"}");
    assertThat(messages.get(3).path("role").asText()).isEqualTo("tool");
    assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("call_1");
    // The provider's own web search exists only on the Anthropic API, so it is left out.
    assertThat(body.path("tools")).hasSize(1);
    assertThat(body.path("tools").get(0).path("function").path("name").asText()).isEqualTo("lookup");
  }

  @Test
  void aModelThatCannotUseToolsIsSentNone() {
    var body = client(ModelRefs.OLLAMA, info(false)).buildRequest("llama3:8b",
        List.of(Msg.user("hi")), List.of(LOOKUP), false);
    assertThat(body.has("tools")).isFalse();
    assertThat(body.has("max_tokens")).isTrue();
  }

  @Test
  void readsAReplyWithToolCallsAndUsage() {
    replies.add(new Reply(200, """
        {"model":"gpt-5.1","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
        "content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"lookup",
        "arguments":"{\\"q\\":\\"tea\\"}"}}]}}],
        "usage":{"prompt_tokens":120,"completion_tokens":8,"prompt_tokens_details":{"cached_tokens":100}}}
        """, "application/json"));

    LlmResponse r = client(ModelRefs.OPENAI, info(true)).complete("gpt-5.1", List.of(Msg.user("tea?")), List.of(LOOKUP));

    assertThat(auth).containsExactly("Bearer sk-own-key");
    assertThat(r.wantsTools()).isTrue();
    assertThat(r.stopReason()).isEqualTo("tool_use");
    assertThat(r.toolCalls().get(0).args().path("q").asText()).isEqualTo("tea");
    assertThat(r.inputTokens()).isEqualTo(120);
    assertThat(r.cacheReadTokens()).isEqualTo(100);
    assertThat(r.outputTokens()).isEqualTo(8);
    // The reply is kept as it came, so the next round sends it back unchanged.
    var next = client(ModelRefs.OPENAI, info(true)).buildRequest("gpt-5.1",
        List.of(Msg.user("tea?"), Msg.assistantTurn(r)), List.of(LOOKUP), false);
    assertThat(next.path("messages").get(1).path("tool_calls").get(0).path("id").asText()).isEqualTo("c1");
  }

  @Test
  void streamsTextAndAssemblesToolCallsFromFragments() {
    replies.add(new Reply(200, String.join("\n",
        "data: {\"choices\":[{\"delta\":{\"content\":\"Let me \"}}]}",
        "data: {\"choices\":[{\"delta\":{\"content\":\"check.\"}}]}",
        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c9\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\"}}]}}]}",
        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"milk\\\"}\"}}]}}]}",
        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":12}}",
        "data: [DONE]", ""), "text/event-stream"));
    StringBuilder seen = new StringBuilder();

    LlmResponse r = client(ModelRefs.OPENROUTER, info(true)).stream("x/y", List.of(Msg.user("milk?")),
        List.of(LOOKUP), seen::append);

    assertThat(seen.toString()).isEqualTo("Let me check.");
    assertThat(r.toolCalls()).hasSize(1);
    assertThat(r.toolCalls().get(0).id()).isEqualTo("c9");
    assertThat(r.toolCalls().get(0).args().path("q").asText()).isEqualTo("milk");
    assertThat(r.inputTokens()).isEqualTo(50);
    assertThat(r.outputTokens()).isEqualTo(12);
    assertThat(requests.get(0).path("stream_options").path("include_usage").asBoolean()).isTrue();
  }

  @Test
  void aBusyProviderIsTriedAgain() {
    replies.add(new Reply(429, "{\"error\":{\"message\":\"slow down\"}}", "application/json"));
    replies.add(new Reply(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"hi\"}}]}",
        "application/json"));

    LlmResponse r = client(ModelRefs.OPENAI, info(true)).complete("gpt-5.1", List.of(Msg.user("hi")), List.of());

    assertThat(r.text()).isEqualTo("hi");
    assertThat(r.stopReason()).isEqualTo("end_turn");
    assertThat(pauses).hasSize(1);
  }

  @Test
  void aRefusedRequestIsReportedWithoutRetrying() {
    replies.add(new Reply(401, "{\"error\":{\"message\":\"bad key\"}}", "application/json"));
    assertThatThrownBy(() -> client(ModelRefs.OPENAI, info(true))
        .complete("gpt-5.1", List.of(Msg.user("hi")), List.of()))
        .isInstanceOf(JizzaException.class).hasMessageContaining("401");
    assertThat(requests).hasSize(1);
  }

  @Test
  void quickAndDeepSetTheEffortAndAModelThatRefusesItIsAskedAgainWithout() {
    replies.add(new Reply(400, "{\"error\":{\"message\":\"Unsupported parameter: 'reasoning_effort'\"}}",
        "application/json"));
    replies.add(new Reply(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}",
        "application/json"));

    LlmResponse r = LlmCallContext.run(UUID.randomUUID(), "high", () ->
        client(ModelRefs.OPENAI, info(true)).complete("gpt-5.1", List.of(Msg.user("think")), List.of()));

    assertThat(r.text()).isEqualTo("ok");
    assertThat(requests.get(0).path("reasoning_effort").asText()).isEqualTo("high");
    assertThat(requests.get(1).has("reasoning_effort")).isFalse();
  }

  @Test
  void neverSendsWhatLooksLikeACredential() {
    var client = client(ModelRefs.OPENAI, info(true));
    assertThatThrownBy(() -> client.complete("gpt-5.1",
        List.of(Msg.user("my key is sk-ant-api03-" + "A".repeat(80))), List.of()))
        .isInstanceOf(JizzaException.class).hasMessageContaining("credential");
    assertThat(requests).isEmpty();
  }
}
