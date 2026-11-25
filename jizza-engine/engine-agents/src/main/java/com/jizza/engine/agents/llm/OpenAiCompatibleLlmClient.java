package com.jizza.engine.agents.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.activity.JobCancelledException;
import com.jizza.engine.api.chat.ChatFileAccess;
import com.jizza.engine.api.chat.ChatFiles;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.llm.LlmCallContext;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.ModelCatalog;
import com.jizza.engine.api.llm.ModelRefs;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolInputSchemas;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Any provider that speaks the OpenAI chat completions format: OpenAI itself, OpenRouter, Google
 * Gemini's compatible endpoint, a local Ollama, or another server at an address the person gives.
 * Always on the person's own key, never the platform's.
 *
 * <p>It translates Jizza's messages and tools to that format and back, and reports tokens the same
 * way the Anthropic client does, so the agent loop, budgets and usage work unchanged. What the
 * format has no place for is left out rather than faked: the provider's own web search and tool
 * search, and files a model cannot read.
 *
 * <p>The same rules as the Anthropic client hold: the key stays here, request bodies are scrubbed
 * before any log, and a body that still looks like it carries a credential is never sent.
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

  private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 409, 429, 500, 502, 503, 504, 529);
  private static final Set<String> IMAGE_TYPES =
      Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
  private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

  /** The address each provider listens on when the person gives none. */
  public static String defaultBaseUrl(String provider) {
    return switch (provider) {
      case ModelRefs.OPENAI -> "https://api.openai.com/v1";
      case ModelRefs.OPENROUTER -> "https://openrouter.ai/api/v1";
      case ModelRefs.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai";
      case ModelRefs.OLLAMA -> "http://localhost:11434/v1";
      default -> null;
    };
  }

  private final String provider;
  private final AgentProperties props;
  private final RestClient http;
  private final Supplier<ChatFiles> chatFiles;
  /** What is known about a model of this provider, by its own id; null when nothing is. */
  private final Function<String, ModelCatalog.ModelInfo> models;
  private final AnthropicLlmClient.Pause pause;

  public OpenAiCompatibleLlmClient(String provider, String baseUrl, String apiKey,
                                   AgentProperties props, Supplier<ChatFiles> chatFiles,
                                   Function<String, ModelCatalog.ModelInfo> models) {
    this(provider, baseUrl, apiKey, props, chatFiles, models, AnthropicLlmClient::sleepWatchingForStop);
  }

  OpenAiCompatibleLlmClient(String provider, String baseUrl, String apiKey, AgentProperties props,
                            Supplier<ChatFiles> chatFiles,
                            Function<String, ModelCatalog.ModelInfo> models,
                            AnthropicLlmClient.Pause pause) {
    this.provider = provider;
    this.props = props;
    this.chatFiles = chatFiles == null ? () -> null : chatFiles;
    this.models = models == null ? id -> null : models;
    this.pause = pause;
    RestClient.Builder builder = RestClient.builder()
        .baseUrl(trimSlash(baseUrl))
        .requestFactory(AnthropicLlmClient.requestFactory(props.getAnthropic().getTimeout()))
        .defaultHeader("content-type", "application/json");
    if (apiKey != null && !apiKey.isBlank()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
    }
    if (ModelRefs.OPENROUTER.equals(provider)) {
      // OpenRouter shows which app made a call; it learns nothing about the person from these.
      builder.defaultHeader("X-Title", "Jizza");
    }
    this.http = builder.build();
  }

  static String trimSlash(String url) {
    if (url == null) return "";
    String u = url.trim();
    while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
    return u;
  }

  @Override
  public String modelFor(ModelTier tier) {
    throw new IllegalStateException("Models on a person's own key are picked by name, not by tier");
  }

  @Override
  public LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools) {
    ObjectNode body = buildRequest(model, messages, tools, false);
    assertNoSecrets(body);
    return withRetries(body, request -> {
      JsonNode response;
      try {
        response = http.post().uri("/chat/completions").body(request).retrieve().body(JsonNode.class);
      } catch (RestClientResponseException e) {
        throw rejected(e.getStatusCode(), e.getResponseHeaders(), e.getResponseBodyAsString(), e);
      } catch (ResourceAccessException e) {
        throw new AnthropicLlmClient.Transient(AnthropicLlmClient.unreachable(e), null);
      } catch (JizzaException | JobCancelledException e) {
        throw e;
      } catch (RuntimeException e) {
        throw AnthropicLlmClient.unreachable(e);
      }
      if (response == null) {
        throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-empty", "Model call failed",
            "The model provider returned an empty body");
      }
      return parse(response, model);
    });
  }

  @Override
  public LlmResponse stream(String model, List<Msg> messages, List<ToolSpec> tools,
                            Consumer<String> onText) {
    ObjectNode body = buildRequest(model, messages, tools, true);
    assertNoSecrets(body);
    boolean[] reachedUser = {false};
    Consumer<String> watched = piece -> {
      reachedUser[0] = true;
      onText.accept(piece);
    };
    return withRetries(body, request -> {
      try {
        LlmResponse response = http.post().uri("/chat/completions")
            .header("accept", "text/event-stream")
            .body(request)
            .exchange((req, res) -> {
              if (res.getStatusCode().isError()) {
                String error = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                throw rejected(res.getStatusCode(), res.getHeaders(), error, null);
              }
              return readEvents(res.getBody(), watched, model);
            });
        if (response == null) {
          throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-empty", "Model call failed",
              "The model provider returned an empty stream");
        }
        return response;
      } catch (AnthropicLlmClient.Transient e) {
        if (reachedUser[0]) throw e.failure;
        throw e;
      } catch (JizzaException | JobCancelledException e) {
        throw e;
      } catch (ResourceAccessException e) {
        if (reachedUser[0]) throw AnthropicLlmClient.unreachable(e);
        throw new AnthropicLlmClient.Transient(AnthropicLlmClient.unreachable(e), null);
      } catch (RuntimeException e) {
        throw AnthropicLlmClient.unreachable(e);
      }
    });
  }

  /**
   * Runs one call, again while it fails for a passing reason. A provider that refuses the effort
   * setting for this model (not every model reasons) gets the same request once more without it.
   */
  private LlmResponse withRetries(ObjectNode body, Function<ObjectNode, LlmResponse> attempt) {
    AgentProperties.Retry retry = props.getAnthropic().getRetry();
    int attempts = Math.max(1, retry.getMaxAttempts());
    ObjectNode request = body;
    boolean droppedEffort = false;
    for (int n = 1; ; n++) {
      try {
        return attempt.apply(request);
      } catch (AnthropicLlmClient.Transient e) {
        if (n >= attempts) throw e.failure;
        Duration wait = e.retryAfter != null ? e.retryAfter : AnthropicLlmClient.backoff(retry, n);
        if (e.retryAfter != null && e.retryAfter.compareTo(retry.getMaxRetryAfter()) > 0) {
          throw e.failure;
        }
        log.warn("Model call to {} failed ({}); attempt {} of {} in {} ms", provider,
            e.failure.getMessage(), n + 1, attempts, wait.toMillis());
        pause.pause(wait);
      } catch (JizzaException e) {
        if (!droppedEffort && hasEffort(request) && mentionsEffort(e.getMessage())) {
          droppedEffort = true;
          request = withoutEffort(request);
          n--;
          continue;
        }
        throw e;
      }
    }
  }

  private static boolean hasEffort(ObjectNode body) {
    return body.has("reasoning_effort") || body.has("reasoning");
  }

  private static boolean mentionsEffort(String message) {
    String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
    return m.contains("reasoning_effort") || m.contains("reasoning");
  }

  private static ObjectNode withoutEffort(ObjectNode body) {
    ObjectNode copy = body.deepCopy();
    copy.remove("reasoning_effort");
    copy.remove("reasoning");
    return copy;
  }

  RuntimeException rejected(HttpStatusCode status, HttpHeaders headers, String body, Throwable cause) {
    JizzaException failure = new JizzaException(HttpStatus.BAD_GATEWAY, "llm-upstream",
        "Model call failed", "The model provider (" + provider + ") returned " + status + ": "
            + Redaction.scrub(body == null ? "" : abbreviate(body)), cause);
    if (!RETRYABLE_STATUS.contains(status.value())) return failure;
    return new AnthropicLlmClient.Transient(failure, AnthropicLlmClient.retryAfter(headers));
  }

  private static String abbreviate(String text) {
    return text.length() <= 1000 ? text : text.substring(0, 1000) + "…";
  }

  // ---------------------------------------------------------------- request

  ObjectNode buildRequest(String model, List<Msg> messages, List<ToolSpec> tools, boolean stream) {
    ModelCatalog.ModelInfo info = models.apply(model);
    ObjectNode body = Json.obj();
    body.put("model", model);
    int maxTokens = props.getAnthropic().getMaxOutputTokens();
    // OpenAI's newer models only take the newer name; the others take the older one.
    body.put(ModelRefs.OPENAI.equals(provider) ? "max_completion_tokens" : "max_tokens", maxTokens);
    if (stream) {
      body.put("stream", true);
      body.putObject("stream_options").put("include_usage", true);
    }
    String effort = LlmCallContext.effort().orElse(null);
    if (effort != null && reasons(model)) {
      String level = switch (effort.toLowerCase(Locale.ROOT)) {
        case "low" -> "low";
        case "high", "xhigh", "max" -> "high";
        default -> "medium";
      };
      if (ModelRefs.OPENROUTER.equals(provider)) body.putObject("reasoning").put("effort", level);
      else body.put("reasoning_effort", level);
    }

    ArrayNode out = body.putArray("messages");
    StringBuilder system = new StringBuilder();
    for (Msg m : messages) {
      if (m.role() == Msg.Role.SYSTEM && m.text() != null && !m.text().isBlank()) {
        if (!system.isEmpty()) system.append("\n\n");
        system.append(m.text());
      }
    }
    if (!system.isEmpty()) out.addObject().put("role", "system").put("content", system.toString());

    Map<Msg, List<ChatFiles.Content>> attached = attachedFiles(messages, info);
    for (Msg m : messages) {
      switch (m.role()) {
        case SYSTEM -> { /* gathered above */ }
        case USER -> out.add(userMessage(m.text(), attached.getOrDefault(m, List.of())));
        case ASSISTANT -> out.add(assistantMessage(m));
        case TOOL -> {
          for (Msg.ToolResultRef ref : m.toolResults()) {
            out.addObject().put("role", "tool").put("tool_call_id", ref.toolCallId())
                .put("content", ref.result().ok()
                    ? Json.write(ref.result().output())
                    : "Tool failed: " + ref.result().error());
          }
        }
      }
    }

    boolean canUseTools = info == null || info.tools();
    if (tools != null && !tools.isEmpty() && canUseTools) {
      List<ToolSpec> ordered = new ArrayList<>(tools);
      ordered.sort(Comparator.comparing(ToolSpec::name));
      ArrayNode apiTools = Json.mapper().createArrayNode();
      for (ToolSpec spec : ordered) {
        // The provider's own web search and reader exist only on the Anthropic API.
        if (spec.serverTool()) continue;
        ObjectNode fn = apiTools.addObject().put("type", "function").putObject("function");
        fn.put("name", spec.name());
        fn.put("description", spec.description() == null ? "" : spec.description());
        JsonNode schema = ToolInputSchemas.needsChange(spec.inputSchema())
            ? ToolInputSchemas.forModel(spec.inputSchema()) : spec.inputSchema();
        fn.set("parameters", schema == null ? Json.obj().put("type", "object") : schema);
      }
      if (!apiTools.isEmpty()) body.set("tools", apiTools);
    }
    return body;
  }

  /** Models that take an effort level. Unknown ones are tried; a refusal drops it (see above). */
  boolean reasons(String model) {
    String id = model.toLowerCase(Locale.ROOT);
    int slash = id.lastIndexOf('/');
    String bare = slash >= 0 ? id.substring(slash + 1) : id;
    return switch (provider) {
      case ModelRefs.OPENAI -> bare.startsWith("o1") || bare.startsWith("o3") || bare.startsWith("o4")
          || bare.startsWith("gpt-5");
      case ModelRefs.GEMINI -> bare.contains("2.5") || bare.startsWith("gemini-3");
      case ModelRefs.OPENROUTER -> true;
      default -> false;
    };
  }

  private ObjectNode userMessage(String text, List<ChatFiles.Content> files) {
    ObjectNode message = Json.obj().put("role", "user");
    if (files.isEmpty()) {
      message.put("content", text == null ? "" : text);
      return message;
    }
    ArrayNode content = message.putArray("content");
    for (ChatFiles.Content file : files) {
      String type = file.contentType().toLowerCase(Locale.ROOT);
      String data = "data:" + type + ";base64," + Base64.getEncoder().encodeToString(file.bytes());
      if (type.equals("application/pdf")) {
        content.addObject().put("type", "file").putObject("file")
            .put("filename", file.name() == null ? "document.pdf" : file.name())
            .put("file_data", data);
      } else {
        content.addObject().put("type", "image_url").putObject("image_url").put("url", data);
      }
    }
    content.addObject().put("type", "text").put("text", text == null ? "" : text);
    return message;
  }

  /** The key a provider's own reply is kept under in {@link Msg#providerContent}. */
  static final String KEPT = "openai_message";

  private ObjectNode assistantMessage(Msg msg) {
    for (JsonNode kept : msg.providerContent()) {
      // The provider's own reply, unchanged, so anything it needs back (reasoning) comes back.
      if (kept.has(KEPT) && kept.get(KEPT) instanceof ObjectNode original) return original.deepCopy();
    }
    ObjectNode message = Json.obj().put("role", "assistant");
    if (msg.text() != null && !msg.text().isBlank()) message.put("content", msg.text());
    else message.putNull("content");
    if (!msg.toolCalls().isEmpty()) {
      ArrayNode calls = message.putArray("tool_calls");
      for (ToolCall call : msg.toolCalls()) {
        ObjectNode c = calls.addObject().put("id", call.id()).put("type", "function");
        c.putObject("function").put("name", call.name())
            .put("arguments", call.args() == null ? "{}" : Json.write(call.args()));
      }
    }
    return message;
  }

  /**
   * The files each user message carries, as the Anthropic client picks them: the newest few the
   * person whose turn this is attached. Only the kinds this model reads: photos for a model that
   * sees, PDFs for one that reads them.
   */
  private Map<Msg, List<ChatFiles.Content>> attachedFiles(List<Msg> messages,
                                                          ModelCatalog.ModelInfo info) {
    var reader = ChatFileAccess.reader();
    ChatFiles store = chatFiles.get();
    if (reader.isEmpty() || store == null) return Map.of();
    boolean images = info == null || info.vision();
    boolean pdfs = info != null && info.pdf();
    if (!images && !pdfs) return Map.of();
    UUID userId = reader.get();
    Map<Msg, List<ChatFiles.Content>> out = new IdentityHashMap<>();
    Map<UUID, Boolean> seen = new LinkedHashMap<>();
    long total = 0;
    for (int i = messages.size() - 1; i >= 0 && seen.size() < AnthropicLlmClient.MAX_FILES; i--) {
      Msg msg = messages.get(i);
      if (msg.role() != Msg.Role.USER || msg.text() == null) continue;
      List<ChatFiles.Content> files = new ArrayList<>();
      Matcher m = AnthropicLlmClient.FILE_REF.matcher(msg.text());
      while (m.find() && seen.size() < AnthropicLlmClient.MAX_FILES) {
        UUID id;
        try {
          id = UUID.fromString(m.group(1));
        } catch (IllegalArgumentException e) {
          continue;
        }
        if (seen.putIfAbsent(id, true) != null) continue;
        ChatFiles.Content file;
        try {
          file = store.content(userId, id).orElse(null);
        } catch (RuntimeException e) {
          log.warn("Could not read chat file {}", id, e);
          file = null;
        }
        if (file == null || file.contentType() == null) continue;
        String type = file.contentType().toLowerCase(Locale.ROOT);
        boolean ok = (pdfs && type.equals("application/pdf"))
            || (images && IMAGE_TYPES.contains(type) && file.bytes().length <= MAX_IMAGE_BYTES);
        if (!ok || total + file.bytes().length > AnthropicLlmClient.MAX_FILE_BYTES) continue;
        total += file.bytes().length;
        files.add(file);
      }
      if (!files.isEmpty()) out.put(msg, files);
    }
    return out;
  }

  // ---------------------------------------------------------------- response

  LlmResponse parse(JsonNode response, String requested) {
    JsonNode choice = response.path("choices").path(0);
    JsonNode message = choice.path("message");
    String text = message.path("content").isTextual() ? message.path("content").asText() : "";
    List<ToolCall> calls = new ArrayList<>();
    for (JsonNode call : message.path("tool_calls")) {
      calls.add(new ToolCall(callId(call.path("id").asText(null), calls.size()),
          call.path("function").path("name").asText(), arguments(call.path("function").path("arguments"))));
    }
    ObjectNode kept = message.isObject() ? ((ObjectNode) message).deepCopy() : Json.obj();
    kept.put("role", "assistant");
    if (!calls.isEmpty()) fixCallIds(kept, calls);
    Usage usage = Usage.of(response.path("usage"));
    return new LlmResponse(text, calls, stopReason(choice.path("finish_reason").asText(null), calls),
        usage.input, usage.output, response.path("model").asText(requested), usage.cached, 0,
        List.of(Json.obj().set(KEPT, kept)));
  }

  /** Some servers (older Ollama) leave tool calls without ids; the loop needs one to answer. */
  private static String callId(String id, int index) {
    return id == null || id.isBlank() ? "call_" + index + "_" + UUID.randomUUID().toString().substring(0, 8) : id;
  }

  private static void fixCallIds(ObjectNode message, List<ToolCall> calls) {
    ArrayNode list = message.putArray("tool_calls");
    for (ToolCall call : calls) {
      ObjectNode c = list.addObject().put("id", call.id()).put("type", "function");
      c.putObject("function").put("name", call.name()).put("arguments", Json.write(call.args()));
    }
  }

  /** Arguments come as a JSON string (OpenAI) or an object (some local servers). */
  static JsonNode arguments(JsonNode raw) {
    if (raw == null || raw.isMissingNode() || raw.isNull()) return Json.obj();
    if (raw.isObject()) return raw;
    String s = raw.asText("");
    if (s.isBlank()) return Json.obj();
    try {
      JsonNode parsed = Json.read(s);
      return parsed.isObject() ? parsed : Json.obj();
    } catch (RuntimeException e) {
      return Json.obj();
    }
  }

  /** The loop reads Anthropic's stop reasons; these are the same moments in the other format. */
  static String stopReason(String finish, List<ToolCall> calls) {
    if (!calls.isEmpty()) return "tool_use";
    if (finish == null) return "end_turn";
    return switch (finish) {
      case "length" -> "max_tokens";
      case "tool_calls", "function_call" -> "tool_use";
      default -> "end_turn";
    };
  }

  private record Usage(int input, int output, int cached) {
    static Usage of(JsonNode usage) {
      int cached = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
      return new Usage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0),
          cached);
    }
  }

  LlmResponse readEvents(InputStream in, Consumer<String> onText, String requested)
      throws java.io.IOException {
    StringBuilder text = new StringBuilder();
    Map<Integer, StringBuilder> args = new TreeMap<>();
    Map<Integer, String[]> calls = new TreeMap<>(); // index -> {id, name}
    String finish = null;
    String model = requested;
    Usage usage = new Usage(0, 0, 0);
    ObjectNode extra = Json.obj();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.startsWith("data:")) continue;
        String data = line.substring(5).trim();
        if (data.isEmpty() || data.equals("[DONE]")) continue;
        JsonNode event = Json.read(data);
        if (event.has("error")) {
          throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-upstream", "Model call failed",
              "The model provider (" + provider + ") reported: "
                  + Redaction.scrub(event.path("error").path("message").asText(event.path("error").asText())));
        }
        if (event.hasNonNull("model")) model = event.path("model").asText(model);
        if (event.path("usage").isObject()) usage = Usage.of(event.path("usage"));
        for (JsonNode choice : event.path("choices")) {
          JsonNode delta = choice.path("delta");
          if (delta.path("content").isTextual()) {
            String piece = delta.path("content").asText();
            if (!piece.isEmpty()) {
              text.append(piece);
              onText.accept(piece);
            }
          }
          // Reasoning some providers return beside the answer is kept to send back, never shown.
          if (delta.has("reasoning_details") && delta.get("reasoning_details").isArray()) {
            ArrayNode details = extra.has("reasoning_details")
                ? (ArrayNode) extra.get("reasoning_details") : extra.putArray("reasoning_details");
            delta.get("reasoning_details").forEach(d -> details.add(d.deepCopy()));
          }
          for (JsonNode call : delta.path("tool_calls")) {
            int index = call.path("index").asInt(calls.size());
            String[] known = calls.computeIfAbsent(index, k -> new String[2]);
            if (call.hasNonNull("id")) known[0] = call.path("id").asText();
            JsonNode fn = call.path("function");
            if (fn.hasNonNull("name")) known[1] = fn.path("name").asText();
            JsonNode a = fn.path("arguments");
            if (a.isTextual()) args.computeIfAbsent(index, k -> new StringBuilder()).append(a.asText());
            else if (a.isObject()) args.put(index, new StringBuilder(Json.write(a)));
          }
          if (choice.hasNonNull("finish_reason")) finish = choice.path("finish_reason").asText();
        }
      }
    } catch (JizzaException e) {
      throw e;
    } catch (java.io.IOException e) {
      throw new ResourceAccessException("The stream was interrupted: " + e.getMessage(), e);
    }
    List<ToolCall> toolCalls = new ArrayList<>();
    for (Map.Entry<Integer, String[]> e : calls.entrySet()) {
      String[] c = e.getValue();
      if (c[1] == null) continue;
      StringBuilder a = args.get(e.getKey());
      toolCalls.add(new ToolCall(callId(c[0], toolCalls.size()), c[1],
          arguments(a == null ? null : Json.mapper().getNodeFactory().textNode(a.toString()))));
    }
    ObjectNode kept = Json.obj().put("role", "assistant");
    if (text.isEmpty()) kept.putNull("content");
    else kept.put("content", text.toString());
    if (extra.has("reasoning_details")) kept.set("reasoning_details", extra.get("reasoning_details"));
    if (!toolCalls.isEmpty()) fixCallIds(kept, toolCalls);
    return new LlmResponse(text.toString(), toolCalls, stopReason(finish, toolCalls), usage.input,
        usage.output, model, usage.cached, 0, List.of(Json.obj().set(KEPT, kept)));
  }

  /** As in the Anthropic client: never send what looks like a credential; file bytes excepted. */
  void assertNoSecrets(ObjectNode body) {
    ObjectNode copy = body.deepCopy();
    for (JsonNode message : copy.path("messages")) {
      for (JsonNode part : message.path("content")) {
        if (part.path("file") instanceof ObjectNode f) f.remove("file_data");
        if (part.path("image_url") instanceof ObjectNode i) i.remove("url");
      }
      if (message instanceof ObjectNode m) m.remove("reasoning_details");
    }
    if (Redaction.containsSecretLike(Json.write(copy))) {
      log.error("Refusing to send a model request that appears to contain credential material");
      throw new JizzaException(HttpStatus.INTERNAL_SERVER_ERROR, "secret-leak-prevented",
          "Model request blocked",
          "The request being sent to the model appeared to contain credential material and was "
              + "blocked. The model only ever receives prompts and tool schemas.");
    }
  }
}
