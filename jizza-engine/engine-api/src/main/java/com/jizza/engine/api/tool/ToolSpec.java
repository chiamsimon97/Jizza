package com.jizza.engine.api.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the model is allowed to see about a tool: name, description and a JSON Schema for its input.
 * Never carries credentials — auth is injected server-side by the executor (R11).
 */
public record ToolSpec(String name, String description, JsonNode inputSchema, boolean sideEffecting) {
  public ToolSpec(String name, String description, JsonNode inputSchema) {
    this(name, description, inputSchema, false);
  }
}
