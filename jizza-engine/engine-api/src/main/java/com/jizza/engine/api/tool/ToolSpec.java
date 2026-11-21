package com.jizza.engine.api.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the model is allowed to see about a tool: name, description and a JSON Schema for its input.
 * Never carries credentials — auth is injected server-side by the executor (R11).
 *
 * @param deferrable the tool may be left out of the request up front and found by the model with a
 *                 search when a request needs it. For large sets that are rarely needed on a
 *                 given turn (each paired device's actions, an MCP server's tools), so they do not
 *                 fill every request. The client decides: it defers them only when there are
 *                 enough of them to be worth a search, and a client that cannot search sends them
 *                 like any other tool.
 */
public record ToolSpec(String name, String description, JsonNode inputSchema, boolean sideEffecting,
                       boolean deferrable) {

  public ToolSpec(String name, String description, JsonNode inputSchema, boolean sideEffecting) {
    this(name, description, inputSchema, sideEffecting, false);
  }

  public ToolSpec(String name, String description, JsonNode inputSchema) {
    this(name, description, inputSchema, false, false);
  }

  /** The same tool, allowed to be found by search instead of shown up front. */
  public ToolSpec asDeferrable() {
    return deferrable ? this : new ToolSpec(name, description, inputSchema, sideEffecting, true);
  }
}
