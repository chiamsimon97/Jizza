package com.jizza.evals;

import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** A model that says what it is told to, so the harness itself can be tested for free. */
final class ScriptedModel implements LlmClient {

  private final Deque<LlmResponse> script = new ArrayDeque<>();
  final java.util.List<List<Msg>> requests = new java.util.ArrayList<>();

  ScriptedModel callTool(String name, String argsJson) {
    script.add(new LlmResponse("", List.of(new ToolCall("tu_" + script.size(), name,
        Json.read(argsJson))), "tool_use", 100, 20, "scripted"));
    return this;
  }

  ScriptedModel answer(String text) {
    script.add(new LlmResponse(text, List.of(), "end_turn", 120, 40, "scripted"));
    return this;
  }

  @Override
  public LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools) {
    requests.add(List.copyOf(messages));
    if (script.isEmpty()) throw new IllegalStateException("The script ran out");
    return script.poll();
  }

  @Override
  public String modelFor(ModelTier tier) { return "scripted"; }
}
