package com.jizza.engine.api.llm;

import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import java.util.List;

/**
 * One turn in the LLM conversation. Only text the platform deliberately places here reaches the
 * model; secrets and tokens never do (R11).
 */
public record Msg(Role role, String text, List<ToolCall> toolCalls, List<ToolResultRef> toolResults) {

  public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

  public record ToolResultRef(String toolCallId, ToolResult result) {}

  public static Msg system(String text) { return new Msg(Role.SYSTEM, text, List.of(), List.of()); }
  public static Msg user(String text) { return new Msg(Role.USER, text, List.of(), List.of()); }
  public static Msg assistant(String text) { return new Msg(Role.ASSISTANT, text, List.of(), List.of()); }

  public static Msg assistantToolCalls(String text, List<ToolCall> calls) {
    return new Msg(Role.ASSISTANT, text, calls, List.of());
  }

  public static Msg toolResults(List<ToolResultRef> results) {
    return new Msg(Role.TOOL, null, List.of(), results);
  }
}
