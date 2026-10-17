package com.jizza.engine.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import java.util.List;

/**
 * One turn in the LLM conversation. Only text the platform deliberately places here reaches the
 * model; secrets and tokens never do (R11).
 *
 * @param providerContent for an assistant turn the model wrote in this run, its reply as the
 *                        provider returned it (see {@link LlmResponse#providerContent}); sent back
 *                        verbatim so the history stays append-only. Empty everywhere else.
 */
public record Msg(Role role, String text, List<ToolCall> toolCalls, List<ToolResultRef> toolResults,
                  List<JsonNode> providerContent) {

  public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

  public record ToolResultRef(String toolCallId, ToolResult result) {}

  public Msg {
    toolCalls = toolCalls == null ? List.of() : toolCalls;
    toolResults = toolResults == null ? List.of() : toolResults;
    providerContent = providerContent == null ? List.of() : List.copyOf(providerContent);
  }

  public Msg(Role role, String text, List<ToolCall> toolCalls, List<ToolResultRef> toolResults) {
    this(role, text, toolCalls, toolResults, List.of());
  }

  public static Msg system(String text) { return new Msg(Role.SYSTEM, text, List.of(), List.of()); }
  public static Msg user(String text) { return new Msg(Role.USER, text, List.of(), List.of()); }
  public static Msg assistant(String text) { return new Msg(Role.ASSISTANT, text, List.of(), List.of()); }

  public static Msg assistantToolCalls(String text, List<ToolCall> calls) {
    return new Msg(Role.ASSISTANT, text, calls, List.of());
  }

  /** The model's own tool-use turn, kept exactly as it came back. */
  public static Msg assistantTurn(LlmResponse response) {
    return new Msg(Role.ASSISTANT, response.text(), response.toolCalls(), List.of(),
        response.providerContent());
  }

  public static Msg toolResults(List<ToolResultRef> results) {
    return new Msg(Role.TOOL, null, List.of(), results);
  }
}
