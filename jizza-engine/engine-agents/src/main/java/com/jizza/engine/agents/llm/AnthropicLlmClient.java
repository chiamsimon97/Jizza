package com.jizza.engine.agents.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.JobCancelledException;
import com.jizza.engine.api.chat.ChatFiles;
import com.jizza.engine.api.chat.ChatFileAccess;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolInputSchemas;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The Anthropic Messages API behind {@link LlmClient}. The API key lives here, server-side, and is
 * never handed to a caller or written to a log.
 *
 * <p>Every request body is scrubbed before it can be logged, and the client refuses to send a
 * payload that still looks like it carries credential material -- the invariant is that the model
 * only ever sees prompts and tool <em>schemas</em> (R11).
 *
 * <p>A call that fails for a passing reason (rate limited, overloaded, a provider error, a dropped
 * connection) is tried again with backoff, honouring the provider's {@code retry-after}. A stream
 * is only retried before any of its text reached the user, so nobody sees a reply start twice.
 *
 * <p>The tool definitions and the first system block are marked for the provider's prompt cache,
 * and inside a tool loop the conversation so far is cached as well. The model's own content blocks,
 * thinking included, are kept and sent back unchanged, so the history only ever grows at the end.
 *
 * <p>A PDF or photo the user attached to a chat message ({@code <attachment ... ref="<id>">}) goes
 * to the model as a document or image block beside that message's text, so the model reads it
 * itself, scanned pages included. Only in the chat turn of the user who owns the file, and only the
 * newest few files, so a long conversation does not resend every file it ever had.
 */
public class AnthropicLlmClient implements LlmClient {

  private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

  /** Statuses that mean "not now" rather than "not this request". 529 is the provider overloaded. */
  private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 409, 429, 500, 502, 503, 504, 529);
  /** Error types a stream can report mid-way that are worth another attempt. */
  private static final Set<String> RETRYABLE_STREAM_ERRORS =
      Set.of("overloaded_error", "api_error", "rate_limit_error");
  /** A message's reference to a chat file (see the client's attachments.ts). */
  static final Pattern FILE_REF = Pattern.compile(
      "<attachment kind=\"(?:file|image)\" name=\"[^\"]*\" ref=\"([0-9a-fA-F-]{36})\">");
  /** The newest files a request carries; older ones are left to the text read from them. */
  static final int MAX_FILES = 4;
  static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
  private static final Set<String> IMAGE_TYPES =
      Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
  private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
  /** Content blocks whose signature or payload is opaque provider output, not anything we wrote. */
  private static final Set<String> REASONING_BLOCKS = Set.of("thinking", "redacted_thinking");

  /** How the client waits between attempts; replaced in tests so they need not sleep. */
  interface Pause {
    void pause(Duration duration);
  }

  private final AgentProperties props;
  private final RestClient http;
  private final Pause pause;
  /** Where attached chat files come from; absent where nothing stores them. */
  private final Supplier<ChatFiles> chatFiles;

  public AnthropicLlmClient(AgentProperties props) {
    this(props, () -> null);
  }

  public AnthropicLlmClient(AgentProperties props, Supplier<ChatFiles> chatFiles) {
    this(props, AnthropicLlmClient::sleepWatchingForStop, chatFiles);
  }

  AnthropicLlmClient(AgentProperties props, Pause pause) {
    this(props, pause, () -> null);
  }

  AnthropicLlmClient(AgentProperties props, Pause pause, Supplier<ChatFiles> chatFiles) {
    this.props = props;
    this.pause = pause;
    this.chatFiles = chatFiles;
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

    return withRetries(() -> {
      JsonNode response;
      try {
        response = http.post().uri("/v1/messages").body(body).retrieve().body(JsonNode.class);
      } catch (org.springframework.web.client.RestClientResponseException e) {
        throw rejected(e.getStatusCode(), e.getResponseHeaders(), e.getResponseBodyAsString(), e);
      } catch (ResourceAccessException e) {
        throw new Transient(unreachable(e), null);
      } catch (RuntimeException e) {
        throw unreachable(e);
      }
      if (response == null) {
        throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-empty", "Model call failed",
            "The model provider returned an empty body");
      }
      return parse(response);
    });
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

    boolean[] reachedUser = {false};
    java.util.function.Consumer<String> watched = piece -> {
      reachedUser[0] = true;
      onText.accept(piece);
    };
    return withRetries(() -> {
      try {
        LlmResponse response = http.post().uri("/v1/messages")
            .header("accept", "text/event-stream")
            .body(body)
            .exchange((request, res) -> {
              if (res.getStatusCode().isError()) {
                String error = new String(res.getBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
                throw rejected(res.getStatusCode(), res.getHeaders(), error, null);
              }
              return readEvents(res.getBody(), watched);
            });
        if (response == null) {
          throw new JizzaException(HttpStatus.BAD_GATEWAY, "llm-empty", "Model call failed",
              "The model provider returned an empty stream");
        }
        return response;
      } catch (StreamError e) {
        // Overloaded part-way through: worth another go only while the user has seen nothing.
        if (!reachedUser[0] && RETRYABLE_STREAM_ERRORS.contains(e.type)) {
          throw new Transient(e.failure, null);
        }
        throw e.failure;
      } catch (Transient e) {
        if (reachedUser[0]) throw e.failure;
        throw e;
      } catch (JizzaException | JobCancelledException e) {
        // A stop pressed mid-stream ends the read; it is the user's call, not a provider fault.
        throw e;
      } catch (ResourceAccessException e) {
        if (reachedUser[0]) throw unreachable(e);
        throw new Transient(unreachable(e), null);
      } catch (RuntimeException e) {
        throw unreachable(e);
      }
    });
  }

  /**
   * Runs one call, trying it again while it fails for a passing reason. Each wait is the
   * provider's {@code retry-after} when it gave one, otherwise exponential backoff with jitter.
   */
  <T> T withRetries(Supplier<T> attempt) {
    AgentProperties.Retry retry = props.getAnthropic().getRetry();
    int attempts = Math.max(1, retry.getMaxAttempts());
    for (int n = 1; ; n++) {
      try {
        return attempt.get();
      } catch (Transient e) {
        if (n >= attempts) throw e.failure;
        Duration wait = e.retryAfter != null ? e.retryAfter : backoff(retry, n);
        if (e.retryAfter != null && e.retryAfter.compareTo(retry.getMaxRetryAfter()) > 0) {
          throw e.failure;
        }
        log.warn("Model call failed ({}); attempt {} of {} in {} ms", e.failure.getMessage(),
            n + 1, attempts, wait.toMillis());
        pause.pause(wait);
      }
    }
  }

  static Duration backoff(AgentProperties.Retry retry, int failedAttempts) {
    long base = retry.getInitialBackoff().toMillis() << Math.min(failedAttempts - 1, 20);
    long capped = Math.min(base, retry.getMaxBackoff().toMillis());
    // Half fixed, half random, so many waiting callers do not all come back at once.
    return Duration.ofMillis(capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1));
  }

  /** Sleeps in short steps so a stop pressed while waiting ends the wait. */
  private static void sleepWatchingForStop(Duration duration) {
    long until = System.nanoTime() + duration.toNanos();
    try {
      while (true) {
        Activity.checkCancelled();
        long left = until - System.nanoTime();
        if (left <= 0) return;
        Thread.sleep(Duration.ofNanos(Math.min(left, 250_000_000L)));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JobCancelledException();
    }
  }

  /** An error reply, as either a passing failure worth retrying or a final one. */
  RuntimeException rejected(HttpStatusCode status, HttpHeaders headers, String body,
                                    Throwable cause) {
    JizzaException failure = new JizzaException(HttpStatus.BAD_GATEWAY, "llm-upstream",
        "Model call failed", "The model provider returned " + status + ": "
            + Redaction.scrub(body), cause);
    if (!RETRYABLE_STATUS.contains(status.value())) return failure;
    return new Transient(failure, retryAfter(headers));
  }

  private static JizzaException unreachable(RuntimeException e) {
    return new JizzaException(HttpStatus.BAD_GATEWAY, "llm-unreachable", "Model call failed",
        "Could not reach the model provider: " + e.getMessage(), e);
  }

  /** The provider's {@code retry-after}, in seconds; null when absent or unreadable. */
  static Duration retryAfter(HttpHeaders headers) {
    if (headers == null) return null;
    String value = headers.getFirst("retry-after");
    if (value == null || value.isBlank()) return null;
    try {
      double seconds = Double.parseDouble(value.trim());
      return seconds < 0 ? null : Duration.ofMillis((long) (seconds * 1000));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** A failure that may pass; carries the error to report if no attempt succeeds. */
  static final class Transient extends RuntimeException {
    final JizzaException failure;
    final Duration retryAfter;

    Transient(JizzaException failure, Duration retryAfter) {
      super(failure.getMessage(), null, false, false);
      this.failure = failure;
      this.retryAfter = retryAfter;
    }
  }

  /** An {@code error} event inside a stream that had already started. */
  static final class StreamError extends RuntimeException {
    final String type;
    final JizzaException failure;

    StreamError(String type, JizzaException failure) {
      super(failure.getMessage(), null, false, false);
      this.type = type;
      this.failure = failure;
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
    } catch (StreamError e) {
      throw e;
    } catch (java.io.IOException e) {
      // A connection dropped mid-stream is the same passing failure as one that never connected.
      throw new ResourceAccessException("The stream was interrupted: " + e.getMessage(), e);
    }
    return assembler.response();
  }

  /** Builds one response out of the stream's events, in the order the API sends them. */
  static final class StreamAssembler {
    private final java.util.function.Consumer<String> onText;
    private final StringBuilder text = new StringBuilder();
    /** Every content block by index, as the provider would have returned it whole. */
    private final java.util.Map<Integer, ObjectNode> blocks = new java.util.TreeMap<>();
    private final java.util.Map<Integer, StringBuilder> toolJson = new java.util.HashMap<>();
    private String stopReason;
    private String model;
    private int inputTokens;
    private int outputTokens;
    private int cacheReadTokens;
    private int cacheWriteTokens;

    StreamAssembler(java.util.function.Consumer<String> onText) { this.onText = onText; }

    void accept(JsonNode event) {
      switch (event.path("type").asText()) {
        case "message_start" -> {
          JsonNode message = event.path("message");
          model = message.path("model").asText(null);
          usage(message.path("usage"));
        }
        case "content_block_start" -> {
          int index = event.path("index").asInt();
          ObjectNode block = event.path("content_block").deepCopy();
          blocks.put(index, block);
          String type = block.path("type").asText();
          // The provider's own tool calls (a tool search) stream their input too; they are kept
          // whole so the next request carries them back as they were.
          if ("tool_use".equals(type) || "server_tool_use".equals(type)) {
            toolJson.put(index, new StringBuilder());
          }
        }
        case "content_block_delta" -> {
          int index = event.path("index").asInt();
          JsonNode delta = event.path("delta");
          ObjectNode block = blocks.get(index);
          switch (delta.path("type").asText()) {
            case "text_delta" -> {
              String piece = delta.path("text").asText();
              text.append(piece);
              if (block != null) block.put("text", block.path("text").asText("") + piece);
              if (!piece.isEmpty()) onText.accept(piece);
            }
            case "input_json_delta" -> {
              StringBuilder json = toolJson.get(index);
              if (json != null) json.append(delta.path("partial_json").asText());
            }
            // Thinking is never shown, but it is kept whole: the next request in the loop must
            // carry it back exactly, signature and all.
            case "thinking_delta" -> {
              if (block != null) {
                block.put("thinking", block.path("thinking").asText("")
                    + delta.path("thinking").asText());
              }
            }
            case "signature_delta" -> {
              if (block != null) block.put("signature", delta.path("signature").asText());
            }
            default -> { /* citations and anything newer are not used here */ }
          }
        }
        case "message_delta" -> {
          if (event.path("delta").hasNonNull("stop_reason")) {
            stopReason = event.path("delta").path("stop_reason").asText();
          }
          if (event.path("usage").has("output_tokens")) {
            outputTokens = event.path("usage").path("output_tokens").asInt();
          }
          // Newer versions of the API repeat the final input counts here.
          if (event.path("usage").has("input_tokens")) usage(event.path("usage"));
        }
        case "error" -> {
          JsonNode error = event.path("error");
          throw new StreamError(error.path("type").asText(""), new JizzaException(
              HttpStatus.BAD_GATEWAY, "llm-upstream", "Model call failed",
              "The model provider reported: " + Redaction.scrub(error.path("message").asText())));
        }
        default -> { /* ping, content_block_stop, message_stop */ }
      }
    }

    private void usage(JsonNode usage) {
      cacheReadTokens = usage.path("cache_read_input_tokens").asInt(cacheReadTokens);
      cacheWriteTokens = usage.path("cache_creation_input_tokens").asInt(cacheWriteTokens);
      inputTokens = usage.path("input_tokens").asInt() + cacheReadTokens + cacheWriteTokens;
      if (usage.has("output_tokens")) outputTokens = usage.path("output_tokens").asInt();
    }

    LlmResponse response() {
      List<ToolCall> calls = new ArrayList<>();
      List<JsonNode> content = new ArrayList<>();
      blocks.forEach((index, block) -> {
        String type = block.path("type").asText();
        if ("tool_use".equals(type)) {
          String json = toolJson.get(index).toString();
          JsonNode input = json.isBlank() ? Json.obj() : Json.read(json);
          block.set("input", input);
          calls.add(new ToolCall(block.path("id").asText(), block.path("name").asText(), input));
        } else if ("server_tool_use".equals(type)) {
          String json = toolJson.get(index).toString();
          if (!json.isBlank()) block.set("input", Json.read(json));
        }
        content.add(block);
      });
      return new LlmResponse(text.toString(), calls, stopReason, inputTokens, outputTokens, model,
          cacheReadTokens, cacheWriteTokens, content);
    }
  }

  /** The provider's tool search, which finds deferred tools by their names and descriptions. */
  static final String TOOL_SEARCH_TYPE = "tool_search_tool_bm25_20251119";
  static final String TOOL_SEARCH_NAME = "tool_search_tool_bm25";

  ObjectNode buildRequest(String model, List<Msg> messages, List<ToolSpec> tools) {
    AgentProperties.Caching caching = props.getAnthropic().getCaching();
    ObjectNode body = Json.obj();
    body.put("model", model);
    body.put("max_tokens", props.getAnthropic().getMaxOutputTokens());
    String effort = effortFor(model);
    if (effort != null) body.putObject("output_config").put("effort", effort);

    // The first system message is the agent's own instructions, the same on every call. Anything
    // after it changes from turn to turn; where the model accepts it, it goes after the person's
    // message so the conversation before it stays cached.
    int turn = lastUserIndex(messages);
    boolean inline = turn >= 0 && props.getAnthropic().getInlineContext().isEnabled()
        && AgentProperties.matches(model, props.getAnthropic().getInlineContext().getModels());
    List<String> system = new ArrayList<>();
    List<String> context = new ArrayList<>();
    for (Msg msg : messages) {
      if (msg.role() != Msg.Role.SYSTEM) continue;
      if (system.isEmpty() || !inline) system.add(msg.text());
      else context.add(msg.text());
    }

    ArrayNode apiMessages = body.putArray("messages");
    boolean inToolLoop = false;
    ObjectNode beforeTurn = null;
    Map<Msg, List<ChatFiles.Content>> attached = attachedFiles(messages);

    for (int i = 0; i < messages.size(); i++) {
      Msg msg = messages.get(i);
      switch (msg.role()) {
        case SYSTEM -> { /* gathered above */ }
        case USER -> {
          if (i == turn && apiMessages.size() > 0) {
            beforeTurn = (ObjectNode) apiMessages.get(apiMessages.size() - 1);
          }
          apiMessages.add(userMessage(msg.text(), attached.getOrDefault(msg, List.of())));
          if (i == turn && !context.isEmpty()) apiMessages.add(systemNote(context));
        }
        case ASSISTANT -> apiMessages.add(assistantMessage(msg));
        case TOOL -> {
          apiMessages.add(toolResultMessage(msg));
          inToolLoop = true;
        }
      }
    }

    ArrayNode apiTools = null;
    ObjectNode lastShownTool = null;
    if (tools != null && !tools.isEmpty()) {
      apiTools = body.putArray("tools");
      // A stable order, so the same tools always make the same cacheable prefix.
      List<ToolSpec> ordered = new ArrayList<>(tools);
      ordered.sort(Comparator.comparing(ToolSpec::name));
      boolean defer = shouldDefer(ordered);
      for (ToolSpec spec : ordered) {
        ObjectNode tool = apiTools.addObject();
        tool.put("name", spec.name());
        tool.put("description", spec.description() == null ? "" : spec.description());
        tool.set("input_schema", inputSchema(spec));
        if (defer && spec.deferrable()) tool.put("defer_loading", true);
        else lastShownTool = tool;
      }
      if (defer) apiTools.addObject().put("type", TOOL_SEARCH_TYPE).put("name", TOOL_SEARCH_NAME);
    }

    if (!caching.isEnabled()) {
      if (!system.isEmpty()) {
        StringJoiner joined = new StringJoiner("\n\n");
        system.forEach(joined::add);
        body.put("system", joined.toString());
      }
      return body;
    }

    // The provider renders tools, then system, then messages. The first system block is the
    // agent's own instructions, the same on every call; anything after it (what is remembered for
    // this request) varies, so the cache point goes at the end of the first block.
    if (!system.isEmpty()) {
      ArrayNode blocks = body.putArray("system");
      for (int i = 0; i < system.size(); i++) {
        ObjectNode block = blocks.addObject().put("type", "text").put("text", system.get(i));
        if (i == 0) block.set("cache_control", ephemeral());
      }
    } else if (lastShownTool != null) {
      // A deferred tool cannot carry a cache point, so it goes on the last one shown.
      lastShownTool.set("cache_control", ephemeral());
    }
    // With the changing context after the person's message, the conversation before it is the
    // same on the next turn plus one exchange, so it is cached for that turn to read.
    if (inline && beforeTurn != null) markForCache(beforeTurn);
    // Inside a tool loop the next round resends everything so far, so the conversation is cached
    // too; a one-off question is not, since nothing would ever read it back.
    if (inToolLoop) body.set("cache_control", ephemeral());
    return body;
  }

  /** The effort level set for the tier this model serves, when the model accepts one. */
  String effortFor(String model) {
    if (!AgentProperties.matches(model, props.getAnthropic().getEffortModels())) return null;
    for (Map.Entry<ModelTier, String> tier : props.getModels().entrySet()) {
      if (!model.equals(tier.getValue())) continue;
      String effort = props.getAnthropic().getEffort().get(tier.getKey());
      if (effort != null && !effort.isBlank()) return effort.trim().toLowerCase(java.util.Locale.ROOT);
    }
    return null;
  }

  /** Where the person's message for this request is: the last user turn. */
  private static int lastUserIndex(List<Msg> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i).role() == Msg.Role.USER) return i;
    }
    return -1;
  }

  /**
   * Deferrable tools are deferred once there are enough of them to be worth a search, and only
   * while at least one tool stays shown (the provider refuses a request with every tool deferred).
   */
  private boolean shouldDefer(List<ToolSpec> tools) {
    AgentProperties.ToolSearch search = props.getAnthropic().getToolSearch();
    if (!search.isEnabled()) return false;
    long chars = 0;
    boolean anyShown = false;
    for (ToolSpec spec : tools) {
      if (!spec.deferrable()) {
        anyShown = true;
        continue;
      }
      chars += spec.name().length() + (spec.description() == null ? 0 : spec.description().length())
          + (spec.inputSchema() == null ? 0 : spec.inputSchema().toString().length());
    }
    return anyShown && chars > 0 && chars >= search.getMinChars();
  }

  /** The changing context, as a system note inside the conversation. */
  private static ObjectNode systemNote(List<String> context) {
    ObjectNode message = Json.obj();
    message.put("role", "system");
    ArrayNode content = message.putArray("content");
    for (String text : context) {
      content.addObject().put("type", "text").put("text", text == null ? "" : text);
    }
    return message;
  }

  /** A cache point on a message's last block, unless that block cannot carry one. */
  private static void markForCache(ObjectNode message) {
    JsonNode content = message.path("content");
    if (!content.isArray() || content.isEmpty()) return;
    JsonNode last = content.get(content.size() - 1);
    if (!(last instanceof ObjectNode block)) return;
    String type = block.path("type").asText();
    if (REASONING_BLOCKS.contains(type) || type.isEmpty()) return;
    block.set("cache_control", ephemeral());
  }

  /** Tools whose schema was reshaped already, so each is logged once rather than every call. */
  private final Set<String> reshapedTools = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * The provider rejects the whole request when any one tool's schema has oneOf/anyOf/allOf at the
   * top (some MCP servers and paired devices send those), so every tool's schema is made to fit.
   */
  private JsonNode inputSchema(ToolSpec spec) {
    JsonNode schema = spec.inputSchema();
    if (!ToolInputSchemas.needsChange(schema)) return schema;
    if (reshapedTools.add(spec.name())) {
      log.warn("Tool {} has an input schema the model provider does not accept at the top level;"
          + " sending a flattened object schema instead. Original: {}", spec.name(),
          schema == null ? "none" : Redaction.scrub(abbreviate(schema.toString())));
    }
    return ToolInputSchemas.forModel(schema);
  }

  private static String abbreviate(String text) {
    return text.length() <= 2000 ? text : text.substring(0, 2000) + "…";
  }

  private static ObjectNode ephemeral() {
    return Json.obj().put("type", "ephemeral");
  }

  /**
   * The files to send with each user message: the newest {@link #MAX_FILES} referenced, within
   * {@link #MAX_FILE_BYTES}, that belong to the user whose chat turn this is and that the model can
   * read (PDFs and photos). Outside a chat turn there is no user to check, so none.
   */
  private Map<Msg, List<ChatFiles.Content>> attachedFiles(List<Msg> messages) {
    var reader = ChatFileAccess.reader();
    ChatFiles store = chatFiles.get();
    if (reader.isEmpty() || store == null) return Map.of();
    UUID userId = reader.get();
    Map<Msg, List<ChatFiles.Content>> out = new java.util.IdentityHashMap<>();
    Map<UUID, Boolean> seen = new LinkedHashMap<>();
    long total = 0;
    for (int i = messages.size() - 1; i >= 0 && seen.size() < MAX_FILES; i--) {
      Msg msg = messages.get(i);
      if (msg.role() != Msg.Role.USER || msg.text() == null) continue;
      List<ChatFiles.Content> files = new ArrayList<>();
      Matcher m = FILE_REF.matcher(msg.text());
      while (m.find() && seen.size() < MAX_FILES) {
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
        if (file == null || !readable(file) || total + file.bytes().length > MAX_FILE_BYTES) continue;
        total += file.bytes().length;
        files.add(file);
      }
      if (!files.isEmpty()) out.put(msg, files);
    }
    return out;
  }

  private static boolean readable(ChatFiles.Content file) {
    String type = file.contentType() == null ? "" : file.contentType().toLowerCase(java.util.Locale.ROOT);
    return type.equals("application/pdf")
        || (IMAGE_TYPES.contains(type) && file.bytes().length <= MAX_IMAGE_BYTES);
  }

  /** The user's text, with any attached PDFs and photos before it as the provider recommends. */
  private ObjectNode userMessage(String text, List<ChatFiles.Content> files) {
    if (files.isEmpty()) return textMessage("user", text);
    ObjectNode message = Json.obj();
    message.put("role", "user");
    ArrayNode content = message.putArray("content");
    for (ChatFiles.Content file : files) {
      boolean pdf = file.contentType().equalsIgnoreCase("application/pdf");
      ObjectNode block = content.addObject().put("type", pdf ? "document" : "image");
      block.putObject("source").put("type", "base64")
          .put("media_type", file.contentType().toLowerCase(java.util.Locale.ROOT))
          .put("data", Base64.getEncoder().encodeToString(file.bytes()));
      if (pdf) block.put("title", file.name());
    }
    content.addObject().put("type", "text").put("text", text == null ? "" : text);
    return message;
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
    if (!msg.providerContent().isEmpty()) {
      // The model's own reply, unchanged: thinking blocks must come back exactly as they left.
      msg.providerContent().forEach(block -> content.add(block.deepCopy()));
      return message;
    }
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

  LlmResponse parse(JsonNode response) {
    StringBuilder text = new StringBuilder();
    List<ToolCall> calls = new ArrayList<>();
    List<JsonNode> content = new ArrayList<>();
    for (JsonNode block : response.path("content")) {
      content.add(block.deepCopy());
      switch (block.path("type").asText()) {
        case "text" -> text.append(block.path("text").asText());
        case "tool_use" -> calls.add(new ToolCall(block.path("id").asText(),
            block.path("name").asText(), block.path("input")));
        default -> { /* Thinking is kept in the content but never shown. */ }
      }
    }
    JsonNode usage = response.path("usage");
    int cacheRead = usage.path("cache_read_input_tokens").asInt();
    int cacheWrite = usage.path("cache_creation_input_tokens").asInt();
    return new LlmResponse(text.toString(), calls,
        response.path("stop_reason").asText(null),
        usage.path("input_tokens").asInt() + cacheRead + cacheWrite,
        usage.path("output_tokens").asInt(),
        response.path("model").asText(null), cacheRead, cacheWrite, content);
  }

  /**
   * Defence in depth for the "secrets never enter LLM context" invariant. If this ever trips it is
   * a bug in a caller, so the request is refused rather than sent.
   *
   * <p>The bytes of an attached PDF or photo are left out too, for the same reason.
   *
   * <p>The signatures on the model's own thinking blocks are opaque provider output, not anything
   * the platform placed in the prompt, so they are left out of the check rather than risk a random
   * run of characters looking like a key.
   */
  void assertNoSecrets(ObjectNode body) {
    String serialized = Json.write(withoutReasoningSignatures(body));
    if (Redaction.containsSecretLike(serialized)) {
      log.error("Refusing to send a model request that appears to contain credential material");
      throw new JizzaException(HttpStatus.INTERNAL_SERVER_ERROR, "secret-leak-prevented",
          "Model request blocked",
          "The request being sent to the model appeared to contain credential material and was "
              + "blocked. The model only ever receives prompts and tool schemas.");
    }
  }

  private static JsonNode withoutReasoningSignatures(ObjectNode body) {
    ObjectNode copy = body.deepCopy();
    for (JsonNode message : copy.path("messages")) {
      for (JsonNode block : message.path("content")) {
        if (block instanceof ObjectNode o && REASONING_BLOCKS.contains(o.path("type").asText())) {
          o.remove("signature");
          o.remove("data");
        }
        // An attached file's bytes are the user's own document, not text the platform wrote.
        if (block.path("source") instanceof ObjectNode source) source.remove("data");
      }
    }
    return copy;
  }
}
