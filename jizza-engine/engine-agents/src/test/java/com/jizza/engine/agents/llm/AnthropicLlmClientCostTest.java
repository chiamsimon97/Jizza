package com.jizza.engine.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What keeps each request small and cacheable: deferred tools, context placement, effort. */
class AnthropicLlmClientCostTest {

  private static final String INLINE_MODEL = "claude-sonnet-5-5";

  private AgentProperties props;
  private AnthropicLlmClient client;

  @BeforeEach
  void setUp() {
    props = new AgentProperties();
    props.getAnthropic().setApiKey("test-key");
    props.getModels().put(ModelTier.ROUTING, "claude-haiku-5-5");
    props.getModels().put(ModelTier.STANDARD, INLINE_MODEL);
    props.getModels().put(ModelTier.DEEP, "claude-opus-5-5");
    client = new AnthropicLlmClient(props);
  }

  // ── Tool search ───────────────────────────────────────────────────────────

  @Test
  void aFewDeferrableToolsAreSentAsUsualWithoutASearch() {
    var body = client.buildRequest("m", List.of(Msg.user("hi")),
        List.of(plain("workspace_overview"), deferrable("device_chrome__browser_open", 100)));

    assertThat(body.path("tools")).hasSize(2);
    body.path("tools").forEach(t -> assertThat(t.has("defer_loading")).isFalse());
    assertThat(names(body)).doesNotContain(AnthropicLlmClient.TOOL_SEARCH_NAME);
  }

  @Test
  void manyDeferrableToolsAreLeftForTheModelToFindBySearch() {
    List<ToolSpec> tools = new ArrayList<>();
    tools.add(plain("workspace_overview"));
    for (int i = 0; i < 40; i++) tools.add(deferrable("device_chrome__action_" + i, 1_000));

    var body = client.buildRequest("m", List.of(Msg.user("open my bank")), tools);

    JsonNode apiTools = body.path("tools");
    assertThat(apiTools).hasSize(42);
    long deferred = 0;
    for (JsonNode t : apiTools) {
      if (t.path("defer_loading").asBoolean()) {
        deferred++;
        assertThat(t.path("name").asText()).startsWith("device_");
        assertThat(t.has("cache_control")).as("a deferred tool cannot carry a cache point").isFalse();
      }
    }
    assertThat(deferred).isEqualTo(40);
    JsonNode search = apiTools.get(apiTools.size() - 1);
    assertThat(search.path("type").asText()).isEqualTo(AnthropicLlmClient.TOOL_SEARCH_TYPE);
    assertThat(search.path("name").asText()).isEqualTo(AnthropicLlmClient.TOOL_SEARCH_NAME);
    assertThat(search.has("defer_loading")).isFalse();
    // With no system prompt the cache point sits on the one tool still shown.
    JsonNode shown = findTool(apiTools, "workspace_overview");
    assertThat(shown.has("cache_control")).isTrue();
  }

  @Test
  void neverDefersEveryToolSinceTheProviderRefusesThat() {
    List<ToolSpec> tools = new ArrayList<>();
    for (int i = 0; i < 40; i++) tools.add(deferrable("mcp_kite__tool_" + i, 1_000));

    var body = client.buildRequest("m", List.of(Msg.user("hi")), tools);

    body.path("tools").forEach(t -> assertThat(t.has("defer_loading")).isFalse());
    assertThat(names(body)).doesNotContain(AnthropicLlmClient.TOOL_SEARCH_NAME);
  }

  @Test
  void toolSearchCanBeSwitchedOff() {
    props.getAnthropic().getToolSearch().setEnabled(false);
    List<ToolSpec> tools = new ArrayList<>();
    tools.add(plain("workspace_overview"));
    for (int i = 0; i < 40; i++) tools.add(deferrable("device_chrome__action_" + i, 1_000));

    var body = client.buildRequest("m", List.of(Msg.user("hi")), tools);

    assertThat(body.path("tools")).hasSize(41);
    body.path("tools").forEach(t -> assertThat(t.has("defer_loading")).isFalse());
  }

  @Test
  void keepsTheProvidersSearchBlocksWholeAndDoesNotRunThemAsTools() throws Exception {
    String sse = String.join("\n",
        "data: {\"type\":\"message_start\",\"message\":{\"model\":\"m\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}",
        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"tool_search_tool_bm25\",\"input\":{}}}",
        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\": \\\"open page\\\"}\"}}",
        "data: {\"type\":\"content_block_stop\",\"index\":0}",
        "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_search_tool_result\",\"tool_use_id\":\"srvtoolu_1\",\"content\":{\"type\":\"tool_search_tool_search_result\",\"tool_references\":[{\"type\":\"tool_reference\",\"tool_name\":\"device_chrome__browser_open\"}]}}}",
        "data: {\"type\":\"content_block_stop\",\"index\":1}",
        "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"device_chrome__browser_open\",\"input\":{}}}",
        "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"url\\\": \\\"https://example.com\\\"}\"}}",
        "data: {\"type\":\"content_block_stop\",\"index\":2}",
        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":20}}",
        "");

    LlmResponse response = client.readEvents(
        new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8)), p -> { });

    assertThat(response.toolCalls()).extracting(ToolCall::name)
        .containsExactly("device_chrome__browser_open");
    assertThat(response.providerContent()).hasSize(3);
    assertThat(response.providerContent().get(0).path("input").path("query").asText())
        .isEqualTo("open page");
    assertThat(response.providerContent().get(1).path("type").asText())
        .isEqualTo("tool_search_tool_result");

    // Sent back exactly as it came, so the next round of the loop carries the search too.
    var body = client.buildRequest("m", List.of(Msg.user("open example.com"),
        Msg.assistantTurn(response),
        Msg.toolResults(List.of(new Msg.ToolResultRef("toolu_2", ToolResult.ok(Json.obj()))))),
        List.of());
    JsonNode assistant = body.path("messages").get(1).path("content");
    assertThat(assistant).hasSize(3);
    assertThat(assistant.get(0).path("type").asText()).isEqualTo("server_tool_use");
    assertThat(body.path("messages").get(2).path("content")).hasSize(1);
  }

  // ── Context placement and caching ─────────────────────────────────────────

  @Test
  void changingContextGoesAfterThePersonsMessageSoTheConversationStaysCached() {
    List<Msg> messages = List.of(
        Msg.system("You are Jizza."),
        Msg.system("Remembered: likes window seats"),
        Msg.system("Workspace: 3 runs not finished"),
        Msg.user("book the train"),
        Msg.assistant("Which date?"),
        Msg.user("Friday"));

    var body = client.buildRequest(INLINE_MODEL, messages, List.of());

    assertThat(body.path("system")).hasSize(1);
    assertThat(body.path("system").get(0).path("text").asText()).isEqualTo("You are Jizza.");
    assertThat(body.path("system").get(0).has("cache_control")).isTrue();
    JsonNode api = body.path("messages");
    assertThat(api).hasSize(4);
    assertThat(api.get(2).path("role").asText()).isEqualTo("user");
    assertThat(api.get(3).path("role").asText()).isEqualTo("system");
    assertThat(api.get(3).path("content")).hasSize(2);
    assertThat(api.get(3).path("content").get(1).path("text").asText()).contains("3 runs");
    // The conversation before this message is marked for the next turn to read back.
    assertThat(lastBlock(api.get(1)).has("cache_control")).isTrue();
    assertThat(lastBlock(api.get(0)).has("cache_control")).isFalse();
    assertThat(lastBlock(api.get(2)).has("cache_control")).isFalse();
  }

  @Test
  void insideAToolLoopTheNoteStaysRightAfterThePersonsMessage() {
    List<Msg> messages = List.of(
        Msg.system("You are Jizza."),
        Msg.system("Workspace: none"),
        Msg.user("what's running?"),
        Msg.assistantToolCalls("", List.of(new ToolCall("t1", "workspace_list", Json.obj()))),
        Msg.toolResults(List.of(new Msg.ToolResultRef("t1", ToolResult.ok(Json.obj())))));

    var body = client.buildRequest(INLINE_MODEL, messages, List.of());

    JsonNode api = body.path("messages");
    assertThat(api).extracting(m -> m.path("role").asText())
        .containsExactly("user", "system", "assistant", "user");
    assertThat(body.has("cache_control")).isTrue();
  }

  @Test
  void modelsWithoutInConversationNotesKeepTheContextWithTheInstructions() {
    List<Msg> messages = List.of(Msg.system("You are Jizza."), Msg.system("Workspace: none"),
        Msg.user("hi"), Msg.assistant("hello"), Msg.user("again"));

    var body = client.buildRequest("claude-sonnet-5", messages, List.of());

    assertThat(body.path("system")).hasSize(2);
    body.path("messages").forEach(m -> {
      assertThat(m.path("role").asText()).isNotEqualTo("system");
      assertThat(lastBlock(m).has("cache_control")).isFalse();
    });
  }

  @Test
  void aOneOffCallWithOnlyInstructionsIsUnchanged() {
    var body = client.buildRequest(INLINE_MODEL,
        List.of(Msg.system("Sort this message."), Msg.user("hello")), List.of());

    assertThat(body.path("system")).hasSize(1);
    assertThat(body.path("messages")).hasSize(1);
    assertThat(lastBlock(body.path("messages").get(0)).has("cache_control")).isFalse();
  }

  // ── Effort ────────────────────────────────────────────────────────────────

  @Test
  void eachTierSendsItsEffortToModelsThatAcceptOne() {
    props.getAnthropic().getEffort().put(ModelTier.ROUTING, "low");
    props.getAnthropic().getEffort().put(ModelTier.STANDARD, "Medium");
    props.getAnthropic().getEffort().put(ModelTier.DEEP, " ");

    assertThat(effort("claude-haiku-5-5")).isEqualTo("low");
    assertThat(effort(INLINE_MODEL)).isEqualTo("medium");
    assertThat(effort("claude-opus-5-5")).isNull();
    assertThat(effort("some-other-model")).isNull();

    props.getModels().put(ModelTier.ROUTING, "claude-haiku-4-5-20251001");
    assertThat(effort("claude-haiku-4-5-20251001")).as("older models reject effort").isNull();
  }

  // ── Budget ────────────────────────────────────────────────────────────────

  @Test
  void aCacheReadCountsAsATenthOfAFreshTokenInTheRunBudget() {
    var response = new LlmResponse("ok", List.of(), "end_turn", 50_000, 500, "m",
        40_000, 2_000, List.of());

    assertThat(response.budgetTokens()).isEqualTo(8_000 + 2_000 + 4_000 + 500);
    assertThat(response.paused()).isFalse();
    assertThat(new LlmResponse("", List.of(), "pause_turn", 0, 0, "m").paused()).isTrue();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private String effort(String model) {
    var body = client.buildRequest(model, List.of(Msg.user("hi")), List.of());
    return body.has("output_config") ? body.path("output_config").path("effort").asText() : null;
  }

  private static ToolSpec plain(String name) {
    return new ToolSpec(name, "A core tool", Json.obj().put("type", "object"));
  }

  private static ToolSpec deferrable(String name, int descriptionChars) {
    return new ToolSpec(name, "x".repeat(descriptionChars), Json.obj().put("type", "object"),
        false, true);
  }

  private static List<String> names(JsonNode body) {
    List<String> out = new ArrayList<>();
    body.path("tools").forEach(t -> out.add(t.path("name").asText()));
    return out;
  }

  private static JsonNode findTool(JsonNode tools, String name) {
    for (JsonNode t : tools) if (name.equals(t.path("name").asText())) return t;
    throw new AssertionError("no tool " + name);
  }

  private static JsonNode lastBlock(JsonNode message) {
    JsonNode content = message.path("content");
    return content.get(content.size() - 1);
  }
}
