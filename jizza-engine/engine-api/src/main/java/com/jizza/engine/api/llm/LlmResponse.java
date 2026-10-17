package com.jizza.engine.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.tool.ToolCall;
import java.util.List;

/**
 * @param stopReason       raw provider stop reason, e.g. {@code end_turn} or {@code tool_use}
 * @param inputTokens      every input token the call consumed, cached or not, so budgets and
 *                         context limits see the real size of the request
 * @param cacheReadTokens  the part of {@code inputTokens} served from the prompt cache
 * @param cacheWriteTokens the part of {@code inputTokens} written to the prompt cache
 * @param providerContent  the model's reply exactly as the provider returned it, in order, thinking
 *                         blocks and their signatures included. Opaque to everything but the client
 *                         that made it: handed back unchanged when the turn continues, so the model
 *                         keeps its own reasoning across tool calls. Empty when the client keeps none.
 */
public record LlmResponse(String text, List<ToolCall> toolCalls, String stopReason,
                          int inputTokens, int outputTokens, String model,
                          int cacheReadTokens, int cacheWriteTokens,
                          List<JsonNode> providerContent) {

  public LlmResponse {
    toolCalls = toolCalls == null ? List.of() : toolCalls;
    providerContent = providerContent == null ? List.of() : List.copyOf(providerContent);
  }

  public LlmResponse(String text, List<ToolCall> toolCalls, String stopReason,
                     int inputTokens, int outputTokens, String model) {
    this(text, toolCalls, stopReason, inputTokens, outputTokens, model, 0, 0, List.of());
  }

  public boolean wantsTools() { return toolCalls != null && !toolCalls.isEmpty(); }

  /** Input tokens billed at the full rate: neither read from nor written to the cache. */
  public int uncachedInputTokens() {
    return Math.max(0, inputTokens - cacheReadTokens - cacheWriteTokens);
  }

  public static LlmResponse text(String text) {
    return new LlmResponse(text, List.of(), "end_turn", 0, 0, "stub");
  }
}
