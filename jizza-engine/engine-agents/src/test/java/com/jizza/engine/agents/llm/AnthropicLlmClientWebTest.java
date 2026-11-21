package com.jizza.engine.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.llm.WebSources;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Web search and page reading run with the provider; what comes back is shown with its sources. */
class AnthropicLlmClientWebTest {

  private AgentProperties props;
  private AnthropicLlmClient client;

  @BeforeEach
  void setUp() {
    props = new AgentProperties();
    props.getAnthropic().setApiKey("test-key");
    props.getModels().put(ModelTier.ROUTING, "claude-haiku-5-5");
    props.getModels().put(ModelTier.STANDARD, "claude-sonnet-5-5");
    props.getModels().put(ModelTier.DEEP, "claude-opus-5-5");
    client = new AnthropicLlmClient(props);
  }

  private static final ToolSpec SEARCH = new ToolSpec(ToolSpec.WEB_SEARCH, "Search", JsonSchemas.emptyObject());
  private static final ToolSpec FETCH = new ToolSpec(ToolSpec.WEB_FETCH, "Read", JsonSchemas.emptyObject());
  private static final ToolSpec PLAIN = new ToolSpec("workspace_overview", "A core tool",
      Json.obj().put("type", "object"));

  @Test
  void webToolsGoAsTheProvidersOwnToolsWithNoSchema() {
    var body = client.buildRequest("claude-sonnet-5-5", List.of(Msg.user("news?")),
        List.of(PLAIN, SEARCH, FETCH));

    JsonNode search = tool(body, ToolSpec.WEB_SEARCH);
    assertThat(search.path("type").asText()).isEqualTo(AnthropicLlmClient.WEB_SEARCH_TYPE);
    assertThat(search.path("max_uses").asInt()).isEqualTo(5);
    assertThat(search.has("input_schema")).isFalse();
    assertThat(search.has("description")).isFalse();
    JsonNode fetch = tool(body, ToolSpec.WEB_FETCH);
    assertThat(fetch.path("type").asText()).isEqualTo(AnthropicLlmClient.WEB_FETCH_TYPE);
    assertThat(fetch.path("citations").path("enabled").asBoolean()).isTrue();
    assertThat(fetch.path("max_content_tokens").asInt()).isEqualTo(20_000);
  }

  @Test
  void modelsWithoutFilteringGetTheBasicVariants() {
    var body = client.buildRequest("claude-haiku-5-5", List.of(Msg.user("news?")), List.of(SEARCH, FETCH));
    assertThat(tool(body, ToolSpec.WEB_SEARCH).path("type").asText())
        .isEqualTo(AnthropicLlmClient.WEB_SEARCH_BASIC_TYPE);
    assertThat(tool(body, ToolSpec.WEB_FETCH).path("type").asText())
        .isEqualTo(AnthropicLlmClient.WEB_FETCH_BASIC_TYPE);
  }

  @Test
  void blockedSitesAreNeverSearchedOrRead() {
    props.getAnthropic().getWeb().setBlockedDomains(List.of(" Example.com ", "", "example.com", "ads.net"));
    var body = client.buildRequest("claude-sonnet-5-5", List.of(Msg.user("x")), List.of(SEARCH, FETCH));
    assertThat(tool(body, ToolSpec.WEB_SEARCH).path("blocked_domains").toString())
        .isEqualTo("[\"example.com\",\"ads.net\"]");
    assertThat(tool(body, ToolSpec.WEB_FETCH).path("blocked_domains")).hasSize(2);
  }

  @Test
  void switchedOffWebToolsAreLeftOut() {
    props.getAnthropic().getWeb().setEnabled(false);
    var body = client.buildRequest("claude-sonnet-5-5", List.of(Msg.user("x")), List.of(PLAIN, SEARCH));
    assertThat(body.path("tools")).hasSize(1);
  }

  @Test
  void aSearchedReplyKeepsItsSourcesAndOnlyTheAnswerAfterThem() throws Exception {
    String sse = String.join("\n",
        "data: {\"type\":\"message_start\",\"message\":{\"model\":\"m\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}",
        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Let me check. \"}}",
        "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"web_search\",\"input\":{}}}",
        "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"gold rate today\\\"}\"}}",
        "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtoolu_1\",\"content\":[{\"type\":\"web_search_result\",\"url\":\"https://rates.example/gold\",\"title\":\"Gold rates\",\"encrypted_content\":\"abc\"}]}}",
        "data: {\"type\":\"content_block_start\",\"index\":3,\"content_block\":{\"type\":\"text\",\"text\":\"\",\"citations\":null}}",
        "data: {\"type\":\"content_block_delta\",\"index\":3,\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"type\":\"web_search_result_location\",\"url\":\"https://rates.example/gold\",\"title\":\"Gold rates\",\"cited_text\":\"7,250\"}}}",
        "data: {\"type\":\"content_block_delta\",\"index\":3,\"delta\":{\"type\":\"text_delta\",\"text\":\"Gold is 7,250 a gram today.\"}}",
        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}",
        "");
    StringBuilder streamed = new StringBuilder();
    LlmResponse response = client.readEvents(
        new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8)), streamed::append);

    assertThat(streamed.toString()).isEqualTo("Let me check. Gold is 7,250 a gram today.");
    assertThat(response.text()).isEqualTo("Gold is 7,250 a gram today.");
    assertThat(response.toolCalls()).isEmpty();
    assertThat(response.providerContent().get(3).path("citations")).hasSize(1);
    assertThat(WebSources.from(response.providerContent()))
        .containsExactly(new WebSources.Source("https://rates.example/gold", "Gold rates"));
  }

  @Test
  void sourcesPreferWhatWasCitedThenReadAndFallBackToTheTopResults() {
    var cited = Json.obj().put("type", "text").put("text", "x");
    cited.putArray("citations").addObject().put("url", "https://a.example/1").put("title", "A");
    var fetched = Json.obj().put("type", "web_fetch_tool_result");
    fetched.putObject("content").put("url", "https://b.example/page")
        .putObject("content").put("title", " ");
    var results = Json.obj().put("type", "web_search_tool_result");
    var list = results.putArray("content");
    for (int i = 0; i < 7; i++) list.addObject().put("url", "https://c.example/" + i).put("title", "C" + i);
    list.addObject().put("url", "javascript:alert(1)").put("title", "bad");

    assertThat(WebSources.from(List.of(cited, fetched, results)))
        .extracting(WebSources.Source::title).containsExactly("A", "b.example");
    assertThat(WebSources.from(List.of(results))).hasSize(5);
    var failed = Json.obj().put("type", "web_search_tool_result");
    failed.putObject("content").put("type", "web_search_tool_result_error").put("error_code", "max_uses_exceeded");
    assertThat(WebSources.from(List.of(failed))).isEmpty();
  }

  private static JsonNode tool(JsonNode body, String name) {
    for (JsonNode t : body.path("tools")) if (name.equals(t.path("name").asText())) return t;
    throw new AssertionError("no tool " + name);
  }
}
