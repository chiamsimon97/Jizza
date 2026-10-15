package com.jizza.engine.agents.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
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

  /**
   * Server-sent events from the Messages API. Text goes to {@code onText} as each delta arrives;
   * tool calls are assembled from their JSON fragments and returned with the rest of the response,
   * so the caller sees exactly what {@link #complete} would have produced.
   */
  @Override
  public LlmResponse stream(String model, List<Msg> messages, List<ToolSpec> tools,
                            java.util.function.Consumer<String> onText) {
    ObjectNode body = buildRequest(model, messages, tools);
    body.put("stream", true);
    assertNoSecrets(body);
    try {
      LlmResponse response = http.post().uri("/v1/messages")
          .header("accept", "text/event-stream")
          .body(body)
          .exchange((request, res) -> {
            if (res.getStatusCode().isError()) {
              String error = new String(res.getBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8);
              throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-upstream", "Model call failed",
                  "The model provider returned " + res.getStatusCode() + ": "
                      + Redaction.scrub(error));
            }
            return readEvents(res.getBody(), onText);
          });
      if (response == null) {
        throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-empty", "Model call failed",
            "The model provider returned an empty stream");
      }
      return response;
    } catch (JizzaException | com.jizza.engine.api.activity.JobCancelledException e) {
      // A stop pressed mid-stream ends the read; it is the user's call, not a provider fault.
      throw e;
    } catch (RuntimeException e) {
      throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-unreachable", "Model call failed",
          "Could not reach the model provider: " + e.getMessage(), e);
    }
  }

  LlmResponse readEvents(java.io.InputStream in, java.util.function.Consumer<String> onText)
      throws java.io.IOException {
    StreamAssembler assembler = new StreamAssembler(onText);
    try (var reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("data:")) assembler.accept(Json.read(line.substring(5).trim()));
      }
    }
    return assembler.response();
  }

  /** Builds one response out of the stream's events, in the order the API sends them. */
  static final class StreamAssembler {
    private final java.util.function.Consumer<String> onText;
    private final StringBuilder text = new StringBuilder();
    private final java.util.Map<Integer, String[]> toolBlocks = new java.util.TreeMap<>();
    private final java.util.Map<Integer, StringBuilder> toolJson = new java.util.HashMap<>();
    private String stopReason;
    private String model;
    private int inputTokens;
    private int outputTokens;

    StreamAssembler(java.util.function.Consumer<String> onText) { this.onText = onText; }

    void accept(JsonNode event) {
      switch (event.path("type").asText()) {
        case "message_start" -> {
          JsonNode message = event.path("message");
          model = message.path("model").asText(null);
          inputTokens = message.path("usage").path("input_tokens").asInt();
          outputTokens = message.path("usage").path("output_tokens").asInt();
        }
        case "content_block_start" -> {
          JsonNode block = event.path("content_block");
          if ("tool_use".equals(block.path("type").asText())) {
            int index = event.path("index").asInt();
            toolBlocks.put(index, new String[] {block.path("id").asText(), block.path("name").asText()});
            toolJson.put(index, new StringBuilder());
          }
        }
        case "content_block_delta" -> {
          JsonNode delta = event.path("delta");
          switch (delta.path("type").asText()) {
            case "text_delta" -> {
              String piece = delta.path("text").asText();
              text.append(piece);
              if (!piece.isEmpty()) onText.accept(piece);
            }
            case "input_json_delta" -> {
              StringBuilder json = toolJson.get(event.path("index").asInt());
              if (json != null) json.append(delta.path("partial_json").asText());
            }
            default -> { /* thinking and signature deltas are not shown */ }
          }
        }
        case "message_delta" -> {
          if (event.path("delta").hasNonNull("stop_reason")) {
            stopReason = event.path("delta").path("stop_reason").asText();
          }
          if (event.path("usage").has("output_tokens")) {
            outputTokens = event.path("usage").path("output_tokens").asInt();
          }
        }
        case "error" -> throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-upstream",
            "Model call failed", "The model provider reported: "
                + Redaction.scrub(event.path("error").path("message").asText()));
        default -> { /* ping, content_block_stop, message_stop */ }
      }
    }

    LlmResponse response() {
      List<ToolCall> calls = new ArrayList<>();
      toolBlocks.forEach((index, idAndName) -> {
        String json = toolJson.get(index).toString();
        calls.add(new ToolCall(idAndName[0], idAndName[1], json.isBlank() ? Json.obj() : Json.read(json)));
      });
      return new LlmResponse(text.toString(), calls, stopReason, inputTokens, outputTokens, model);
    }
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
