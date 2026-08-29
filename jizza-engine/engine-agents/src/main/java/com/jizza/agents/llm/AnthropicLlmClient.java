package com.jizza.agents.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.agents.config.AgentProperties;
import com.jizza.common.enums.ModelTier;
import com.jizza.common.error.JizzaException;
import com.jizza.common.llm.LlmClient;
import com.jizza.common.llm.LlmResponse;
import com.jizza.common.llm.Msg;
import com.jizza.common.tool.ToolCall;
import com.jizza.common.tool.ToolSpec;
import com.jizza.common.util.Json;
import com.jizza.common.util.Redaction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The Anthropic Messages API behind {@link LlmClient}. The API key lives here, server-side, and is
 * never handed to a caller or written to a log.
 *
 * <p>Every request body is scrubbed before it can be logged, and the client refuses to send a
 * payload that still looks like it carries credential material -- the invariant is that the model
 * only ever sees prompts and tool <em>schemas</em> (R11).
 */
public class AnthropicLlmClient implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

  private final AgentProperties props;
  private final RestClient http;

  public AnthropicLlmClient(AgentProperties props) {
    this.props = props;
    this.http = RestClient.builder()
        .baseUrl(props.getAnthropic().getBaseUrl())
        .requestFactory(requestFactory(props.getAnthropic().getTimeout()))
        .defaultHeader("x-api-key", props.getAnthropic().getApiKey())
        .defaultHeader("anthropic-version", props.getAnthropic().getVersion())
        .defaultHeader("content-type", "application/json")
        .build();
  }

  private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) timeout.toMillis());
    factory.setReadTimeout((int) timeout.toMillis());
    return factory;
  }

  @Override
  public String modelFor(ModelTier tier) {
    String model = props.getModels().get(tier);
    if (model == null || model.isBlank()) {
      throw new IllegalStateException("No model configured for tier " + tier
          + ". Set jizza.agents.models." + tier.name().toLowerCase(java.util.Locale.ROOT) + ".");
    }
    return model;
  }

  @Override
  public LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools) {
    ObjectNode body = buildRequest(model, messages, tools);
    assertNoSecrets(body);

    JsonNode response;
    try {
      response = http.post().uri("/v1/messages").body(body).retrieve().body(JsonNode.class);
    } catch (org.springframework.web.client.RestClientResponseException e) {
      throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-upstream", "Model call failed",
          "The model provider returned " + e.getStatusCode() + ": "
              + Redaction.scrub(e.getResponseBodyAsString()), e);
    } catch (RuntimeException e) {
      throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-unreachable", "Model call failed",
          "Could not reach the model provider: " + e.getMessage(), e);
    }
    if (response == null) {
      throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-empty", "Model call failed",
          "The model provider returned an empty body");
    }
    return parse(response);
  }

  ObjectNode buildRequest(String model, List<Msg> messages, List<ToolSpec> tools) {
    ObjectNode body = Json.obj();
    body.put("model", model);
    body.put("max_tokens", props.getAnthropic().getMaxOutputTokens());

    StringJoiner system = new StringJoiner("\n\n");
    ArrayNode apiMessages = body.putArray("messages");

    for (Msg msg : messages) {
      switch (msg.role()) {
        case SYSTEM -> system.add(msg.text());
        case USER -> apiMessages.add(textMessage("user", msg.text()));
        case ASSISTANT -> apiMessages.add(assistantMessage(msg));
        case TOOL -> apiMessages.add(toolResultMessage(msg));
      }
    }
    if (system.length() > 0) body.put("system", system.toString());

    if (tools != null && !tools.isEmpty()) {
      ArrayNode apiTools = body.putArray("tools");
      for (ToolSpec spec : tools) {
        ObjectNode tool = apiTools.addObject();
        tool.put("name", spec.name());
        tool.put("description", spec.description() == null ? "" : spec.description());
        tool.set("input_schema", spec.inputSchema());
      }
    }
    return body;
  }

  private ObjectNode textMessage(String role, String text) {
    ObjectNode message = Json.obj();
    message.put("role", role);
    ObjectNode block = message.putArray("content").addObject();
    block.put("type", "text");
    block.put("text", text == null ? "" : text);
    return message;
  }

  private ObjectNode assistantMessage(Msg msg) {
    ObjectNode message = Json.obj();
    message.put("role", "assistant");
    ArrayNode content = message.putArray("content");
    if (msg.text() != null && !msg.text().isBlank()) {
      ObjectNode block = content.addObject();
      block.put("type", "text");
      block.put("text", msg.text());
    }
    for (ToolCall call : msg.toolCalls()) {
      ObjectNode block = content.addObject();
      block.put("type", "tool_use");
      block.put("id", call.id());
      block.put("name", call.name());
      block.set("input", call.args());
    }
    return message;
  }

  /** Tool results are returned to the model as a user turn, per the Messages API. */
  private ObjectNode toolResultMessage(Msg msg) {
    ObjectNode message = Json.obj();
    message.put("role", "user");
    ArrayNode content = message.putArray("content");
    for (Msg.ToolResultRef ref : msg.toolResults()) {
      ObjectNode block = content.addObject();
      block.put("type", "tool_result");
      block.put("tool_use_id", ref.toolCallId());
      block.put("is_error", !ref.result().ok());
      block.put("content", ref.result().ok()
          ? Json.write(ref.result().output())
          : "Tool failed: " + ref.result().error());
    }
    return message;
  }

  private LlmResponse parse(JsonNode response) {
    StringBuilder text = new StringBuilder();
    List<ToolCall> calls = new ArrayList<>();
    for (JsonNode block : response.path("content")) {
      switch (block.path("type").asText()) {
        case "text" -> text.append(block.path("text").asText());
        case "tool_use" -> calls.add(new ToolCall(block.path("id").asText(),
            block.path("name").asText(), block.path("input")));
        default -> { /* Unknown block types are ignored rather than failing the turn. */ }
      }
    }
    return new LlmResponse(text.toString(), calls,
        response.path("stop_reason").asText(null),
        response.path("usage").path("input_tokens").asInt(),
        response.path("usage").path("output_tokens").asInt(),
        response.path("model").asText(null));
  }

  /**
   * Defence in depth for the "secrets never enter LLM context" invariant. If this ever trips it is
   * a bug in a caller, so the request is refused rather than sent.
   */
  void assertNoSecrets(ObjectNode body) {
    String serialized = Json.write(body);
    if (Redaction.containsSecretLike(serialized)) {
      log.error("Refusing to send a model request that appears to contain credential material");
      throw new JizzaException(HttpStatus.INTERNAL_SERVER_ERROR, "secret-leak-prevented",
          "Model request blocked",
          "The request being sent to the model appeared to contain credential material and was "
              + "blocked. The model only ever receives prompts and tool schemas.");
    }
  }
}
