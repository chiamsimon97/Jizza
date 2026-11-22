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

  /** The provider's own web search: it runs on the provider's side and never reaches a provider here. */
  public static final String WEB_SEARCH = "web_search";
  /** The provider's own page reader, for links already in the conversation. */
  public static final String WEB_FETCH = "web_fetch";

  /**
   * A tool the model provider runs itself (searching the web, reading a page). The client sends it
   * as the provider's own tool type, and its calls and results come back inside the reply, so no
   * tool provider here is ever asked to run one.
   */
  public boolean serverTool() {
    return WEB_SEARCH.equals(name) || WEB_FETCH.equals(name);
  }

  /** The same tool, allowed to be found by search instead of shown up front. */
  public ToolSpec asDeferrable() {
    return deferrable ? this : new ToolSpec(name, description, inputSchema, sideEffecting, true);
  }
}
