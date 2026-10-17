package com.jizza.engine.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.JsonSchemas;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnthropicLlmClientTest {

  private AgentProperties props;
  private AnthropicLlmClient client;

  @BeforeEach
  void setUp() {
    props = new AgentProperties();
    props.getAnthropic().setApiKey("test-key");
    props.getModels().put(ModelTier.ROUTING, "routing-model");
    props.getModels().put(ModelTier.STANDARD, "standard-model");
    client = new AnthropicLlmClient(props);
  }

  @Test
  void resolvesModelsFromConfigurationRatherThanLiterals() {
    assertThat(client.modelFor(ModelTier.ROUTING)).isEqualTo("routing-model");
    assertThat(client.modelFor(ModelTier.STANDARD)).isEqualTo("standard-model");
    assertThatThrownBy(() -> client.modelFor(ModelTier.DEEP))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jizza.agents.models.deep");
  }

  @Test
  void liftsSystemTurnsOutOfTheMessageListAsTheApiRequires() {
    var body = client.buildRequest("m",
        List.of(Msg.system("rule one"), Msg.system("rule two"), Msg.user("hello")), List.of());

    assertThat(body.path("system").asText()).isEqualTo("rule one\n\nrule two");
    assertThat(body.path("messages")).hasSize(1);
    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
  }

  @Test
  void sendsToolSchemasAndNothingElseAboutATool() {
    var spec = new ToolSpec("send_email", "Send an email",
        JsonSchemas.object(Map.of("to", JsonSchemas.string("recipient")), List.of("to")), true);

    var body = client.buildRequest("m", List.of(Msg.user("hi")), List.of(spec));

    var tool = body.path("tools").get(0);
    assertThat(tool.path("name").asText()).isEqualTo("send_email");
    assertThat(tool.path("input_schema").path("properties").has("to")).isTrue();
    assertThat(tool.has("auth")).isFalse();
    assertThat(tool.has("connection")).isFalse();
  }

  @Test
  void mapsAssistantToolCallsAndToolResultsToApiBlocks() {
    var call = new ToolCall("tu_1", "knowledge_search", Json.obj().put("query", "bp"));
    var messages = List.of(
        Msg.user("how am I?"),
        Msg.assistantToolCalls("let me check", List.of(call)),
        Msg.toolResults(List.of(new Msg.ToolResultRef("tu_1", ToolResult.ok("58 bpm")))));

    var body = client.buildRequest("m", messages, List.of());

    var assistant = body.path("messages").get(1);
    assertThat(assistant.path("role").asText()).isEqualTo("assistant");
    assertThat(assistant.path("content").get(1).path("type").asText()).isEqualTo("tool_use");
    assertThat(assistant.path("content").get(1).path("id").asText()).isEqualTo("tu_1");

    var toolTurn = body.path("messages").get(2);
    assertThat(toolTurn.path("role").asText()).isEqualTo("user");
    assertThat(toolTurn.path("content").get(0).path("type").asText()).isEqualTo("tool_result");
    assertThat(toolTurn.path("content").get(0).path("tool_use_id").asText()).isEqualTo("tu_1");
    assertThat(toolTurn.path("content").get(0).path("is_error").asBoolean()).isFalse();
  }

  @Test
  void marksFailedToolResultsAsErrorsForTheModel() {
    var messages = List.of(Msg.toolResults(
        List.of(new Msg.ToolResultRef("tu_1", ToolResult.error("upstream 500")))));

    var block = client.buildRequest("m", messages, List.of())
        .path("messages").get(0).path("content").get(0);

    assertThat(block.path("is_error").asBoolean()).isTrue();
    assertThat(block.path("content").asText()).contains("upstream 500");
  }

  @Test
  void refusesToSendARequestThatLooksLikeItCarriesCredentials() {
    var leaky = client.buildRequest("m",
        List.of(Msg.user("here is my token: Bearer ya29.AbCdEfGhIjKlMnOpQrStUv")), List.of());

    assertThatThrownBy(() -> client.assertNoSecrets(leaky))
        .isInstanceOf(JizzaException.class)
        .hasMessageContaining("blocked");
  }

  @Test
  void ordinaryRequestsPassTheSecretCheck() {
    var clean = client.buildRequest("m", List.of(Msg.user("what did I spend on groceries?")),
        List.of());
    client.assertNoSecrets(clean);
  }

  @Test
  void assemblesAStreamedReplyAndPassesTextOnAsItArrives() throws Exception {
    String sse = String.join("\n",
        "event: message_start",
        "data: {\"type\":\"message_start\",\"message\":{\"model\":\"m\",\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}}",
        "",
        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Let me \"}}",
        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ask Kai.\"}}",
        "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"delegate_to_specialist\",\"input\":{}}}",
        "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"agent_key\\\": \\\"fin\"}}",
        "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"ance\\\"}\"}}",
        "data: {\"type\":\"content_block_stop\",\"index\":1}",
        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":30}}",
        "data: {\"type\":\"message_stop\"}",
        "");
    List<String> pieces = new java.util.ArrayList<>();

    var response = client.readEvents(
        new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8)), pieces::add);

    assertThat(pieces).containsExactly("Let me ", "ask Kai.");
    assertThat(response.text()).isEqualTo("Let me ask Kai.");
    assertThat(response.wantsTools()).isTrue();
    assertThat(response.toolCalls()).hasSize(1);
    assertThat(response.toolCalls().get(0).name()).isEqualTo("delegate_to_specialist");
    assertThat(response.toolCalls().get(0).args().path("agent_key").asText()).isEqualTo("finance");
    assertThat(response.inputTokens()).isEqualTo(12);
    assertThat(response.outputTokens()).isEqualTo(30);
  }

  @Test
  void anErrorInTheMiddleOfAStreamIsAModelFailure() {
    String sse = "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n";
    assertThatThrownBy(() -> client.readEvents(
        new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8)), t -> { }))
        .isInstanceOf(JizzaException.class);
  }
}
