package com.jizza.common.llm;

import com.jizza.common.tool.ToolCall;
import java.util.List;

/**
 * @param stopReason raw provider stop reason, e.g. {@code end_turn} or {@code tool_use}
 */
public record LlmResponse(String text, List<ToolCall> toolCalls, String stopReason,
                          int inputTokens, int outputTokens, String model) {

  public boolean wantsTools() { return toolCalls != null && !toolCalls.isEmpty(); }

  public static LlmResponse text(String text) {
    return new LlmResponse(text, List.of(), "end_turn", 0, 0, "stub");
  }
}
