package com.jizza.engine.api.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** A model-issued tool invocation. {@code id} correlates the result back to the LLM turn. */
public record ToolCall(String id, String name, JsonNode args) {
  public ToolCall(String name, JsonNode args) { this(null, name, args); }
}
