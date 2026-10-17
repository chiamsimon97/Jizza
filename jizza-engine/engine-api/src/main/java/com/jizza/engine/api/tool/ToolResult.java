package com.jizza.engine.api.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;

public record ToolResult(JsonNode output, boolean ok, String error) {

  public static ToolResult ok(JsonNode output) { return new ToolResult(output, true, null); }

  public static ToolResult ok(String text) {
    return new ToolResult(TextNode.valueOf(text), true, null);
  }

  public static ToolResult error(String message) {
    return new ToolResult(JsonNodeFactory.instance.nullNode(), false, message);
  }
}
