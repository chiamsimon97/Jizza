package com.jizza.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.util.Iterator;
import java.util.Map;

/**
 * The shape of what a tool returned, as a JSON Schema, so workflow steps after it can offer its
 * fields to map, the way Zapier learns a step's fields from a test. Only the shape is kept: types
 * and field names, never the values, which may be someone's holdings or messages.
 */
public final class McpOutputSchema {

  private static final int MAX_DEPTH = 6;
  private static final int MAX_FIELDS = 100;
  /** Items of a list looked at to learn its item shape; later items rarely add fields. */
  private static final int SAMPLED_ITEMS = 25;

  private McpOutputSchema() { }

  public static ObjectNode infer(JsonNode value) {
    return infer(value, 0);
  }

  private static ObjectNode infer(JsonNode value, int depth) {
    ObjectNode schema = Json.obj();
    if (value == null || value.isNull() || value.isMissingNode()) return schema.put("type", "null");
    if (value.isTextual()) return schema.put("type", "string");
    if (value.isBoolean()) return schema.put("type", "boolean");
    if (value.isIntegralNumber()) return schema.put("type", "integer");
    if (value.isNumber()) return schema.put("type", "number");
    if (depth >= MAX_DEPTH) return schema.put("type", value.isArray() ? "array" : "object");
    if (value.isArray()) {
      schema.put("type", "array");
      ObjectNode items = null;
      int seen = 0;
      for (JsonNode item : value) {
        if (seen++ >= SAMPLED_ITEMS) break;
        items = merge(items, infer(item, depth + 1));
      }
      if (items != null) schema.set("items", items);
      return schema;
    }
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
    while (fields.hasNext() && properties.size() < MAX_FIELDS) {
      Map.Entry<String, JsonNode> field = fields.next();
      properties.set(field.getKey(), infer(field.getValue(), depth + 1));
    }
    return schema;
  }

  /** One schema covering both: an object gains the other's fields, a mismatch keeps the first. */
  static ObjectNode merge(ObjectNode a, ObjectNode b) {
    if (a == null) return b;
    if (b == null) return a;
    String ta = a.path("type").asText();
    String tb = b.path("type").asText();
    if ("null".equals(ta)) return b;
    if ("null".equals(tb)) return a;
    if ("integer".equals(ta) && "number".equals(tb)) return b;
    if (!ta.equals(tb)) return a;
    if ("object".equals(ta)) {
      ObjectNode props = (ObjectNode) a.path("properties");
      b.path("properties").fields().forEachRemaining(e -> {
        if (props.size() >= MAX_FIELDS && !props.has(e.getKey())) return;
        JsonNode mine = props.get(e.getKey());
        props.set(e.getKey(), mine instanceof ObjectNode o ? merge(o, (ObjectNode) e.getValue())
            : e.getValue());
      });
    } else if ("array".equals(ta) && b.has("items")) {
      a.set("items", merge(a.path("items") instanceof ObjectNode o ? o : null,
          (ObjectNode) b.get("items")));
    }
    return a;
  }
}
