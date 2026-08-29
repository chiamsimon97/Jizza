package com.jizza.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.agents.config.AgentProperties;
import com.jizza.common.enums.ModelTier;
import com.jizza.common.error.JizzaException;
import com.jizza.common.llm.Msg;
import com.jizza.common.tool.JsonSchemas;
import com.jizza.common.tool.ToolCall;
import com.jizza.common.tool.ToolResult;
import com.jizza.common.tool.ToolSpec;
import com.jizza.common.util.Json;
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
}
