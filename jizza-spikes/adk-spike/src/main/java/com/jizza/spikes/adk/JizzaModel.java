package com.jizza.spikes.adk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jizza's own Claude client as an ADK model. ADK speaks Gemini's types ({@link Content}, {@link
 * Part}); this translates both ways so ADK's loop can drive the client that already streams, retries,
 * caches and keeps thinking blocks.
 *
 * <p>The one thing with no natural home in Gemini's types is a Claude thinking block and its
 * signature, which must go back to the provider unchanged. It travels as a thought part whose
 * signature bytes carry the block: {@code T:<signature>} for thinking, {@code R:<data>} for redacted
 * thinking. ADK keeps such parts in the agent's own history because they carry a signature.
 */
public final class JizzaModel extends BaseLlm {

  private final LlmClient llm;

  public JizzaModel(String model, LlmClient llm) {
    super(model);
    this.llm = llm;
  }

  @Override
  public Flowable<com.google.adk.models.LlmResponse> generateContent(LlmRequest request,
                                                                      boolean stream) {
    List<Msg> messages = toMessages(request);
    List<ToolSpec> tools = toolSpecs(request);
    String model = request.model().orElse(model());
    if (!stream) {
      return Flowable.fromCallable(() -> fromResponse(llm.complete(model, messages, tools)));
    }
    return Flowable.create(emitter -> {
      LlmResponse response = llm.stream(model, messages, tools, piece -> emitter.onNext(
          com.google.adk.models.LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(piece)).build())
              .partial(true).build()));
      emitter.onNext(fromResponse(response));
      emitter.onComplete();
    }, BackpressureStrategy.BUFFER);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest request) {
    throw new UnsupportedOperationException("Live (bidirectional audio) sessions are not used");
  }

  // ── ADK request -> Jizza messages ─────────────────────────────────────────────

  static List<Msg> toMessages(LlmRequest request) {
    List<Msg> messages = new ArrayList<>();
    for (String system : request.getSystemInstructions()) messages.add(Msg.system(system));
    for (Content content : request.contents()) {
      List<Part> parts = content.parts().orElse(List.of());
      if ("model".equals(content.role().orElse(""))) {
        messages.add(assistantTurn(parts));
        continue;
      }
      StringBuilder text = new StringBuilder();
      List<Msg.ToolResultRef> results = new ArrayList<>();
      for (Part part : parts) {
        part.text().ifPresent(t -> text.append(text.isEmpty() ? "" : "\n").append(t));
        part.functionResponse().ifPresent(r -> results.add(
            new Msg.ToolResultRef(r.id().orElse(""), toolResult(r))));
      }
      if (!results.isEmpty()) messages.add(Msg.toolResults(results));
      if (!text.isEmpty()) messages.add(Msg.user(text.toString()));
    }
    return messages;
  }

  /** The model's own turn rebuilt block for block, thinking included, in its original order. */
  private static Msg assistantTurn(List<Part> parts) {
    List<JsonNode> blocks = new ArrayList<>();
    List<ToolCall> calls = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (Part part : parts) {
      if (part.thought().orElse(false) && part.thoughtSignature().isPresent()) {
        blocks.add(reasoningBlock(part));
      } else if (part.functionCall().isPresent()) {
        FunctionCall fc = part.functionCall().get();
        JsonNode input = Json.toNode(fc.args().orElse(Map.of()));
        ObjectNode block = Json.obj().put("type", "tool_use").put("id", fc.id().orElse(""))
            .put("name", fc.name().orElse(""));
        block.set("input", input);
        blocks.add(block);
        calls.add(new ToolCall(fc.id().orElse(""), fc.name().orElse(""), input));
      } else if (part.text().isPresent() && !part.thought().orElse(false)) {
        text.append(part.text().get());
        blocks.add(Json.obj().put("type", "text").put("text", part.text().get()));
      }
    }
    return new Msg(Msg.Role.ASSISTANT, text.toString(), calls, List.of(), blocks);
  }

  private static JsonNode reasoningBlock(Part part) {
    String carried = new String(part.thoughtSignature().get(), StandardCharsets.UTF_8);
    if (carried.startsWith("R:")) {
      return Json.obj().put("type", "redacted_thinking").put("data", carried.substring(2));
    }
    return Json.obj().put("type", "thinking").put("thinking", part.text().orElse(""))
        .put("signature", carried.startsWith("T:") ? carried.substring(2) : carried);
  }

  private static ToolResult toolResult(FunctionResponse response) {
    Map<String, Object> body = response.response().orElse(Map.of());
    if (Boolean.FALSE.equals(body.get("ok"))) return ToolResult.error(String.valueOf(body.get("error")));
    return ToolResult.ok(Json.toNode(body.get("output")));
  }

  /** The tools ADK attached, as Jizza specs; a capability tool hands over its own. */
  private static List<ToolSpec> toolSpecs(LlmRequest request) {
    List<ToolSpec> specs = new ArrayList<>();
    for (BaseTool tool : request.tools().values()) {
      if (tool instanceof CapabilityTool capability) specs.add(capability.spec());
    }
    return specs;
  }

  // ── Jizza response -> ADK response ────────────────────────────────────────────

  static com.google.adk.models.LlmResponse fromResponse(LlmResponse response) {
    List<Part> parts = new ArrayList<>();
    if (!response.providerContent().isEmpty()) {
      for (JsonNode block : response.providerContent()) {
        switch (block.path("type").asText()) {
          case "thinking" -> parts.add(Part.builder().thought(true)
              .text(block.path("thinking").asText(""))
              .thoughtSignature(("T:" + block.path("signature").asText())
                  .getBytes(StandardCharsets.UTF_8)).build());
          case "redacted_thinking" -> parts.add(Part.builder().thought(true)
              .thoughtSignature(("R:" + block.path("data").asText())
                  .getBytes(StandardCharsets.UTF_8)).build());
          case "text" -> parts.add(Part.fromText(block.path("text").asText()));
          case "tool_use" -> parts.add(functionCall(block.path("id").asText(),
              block.path("name").asText(), block.path("input")));
          default -> { }
        }
      }
    } else {
      if (response.text() != null && !response.text().isEmpty()) parts.add(Part.fromText(response.text()));
      for (ToolCall call : response.toolCalls()) {
        parts.add(functionCall(call.id(), call.name(), call.args()));
      }
    }
    return com.google.adk.models.LlmResponse.builder()
        .content(Content.builder().role("model").parts(parts).build())
        .modelVersion(response.model())
        .usageMetadata(GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(response.inputTokens())
            .candidatesTokenCount(response.outputTokens())
            .cachedContentTokenCount(response.cacheReadTokens())
            .build())
        .build();
  }

  @SuppressWarnings("unchecked")
  private static Part functionCall(String id, String name, JsonNode input) {
    Map<String, Object> args = input == null || input.isNull()
        ? Map.of() : Json.mapper().convertValue(input, Map.class);
    return Part.builder().functionCall(FunctionCall.builder().id(id).name(name).args(args).build())
        .build();
  }
}
