package com.jizza.engine.connectors.tools.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;

/**
 * Translates an OpenAPI schema into the plain JSON Schema a tool exposes to the model.
 *
 * <p>Deliberately a narrow translation rather than a serialization of the OpenAPI object: the model
 * should see only what it needs to fill in the call, and vendor extensions or description text
 * copied verbatim out of a third-party document are exactly the untrusted content that has no
 * business in a tool definition.
 */
public final class SchemaTranslator {

  private SchemaTranslator() {}

  /** Guard against a document with a deeply nested or self-referential schema. */
  private static final int MAX_DEPTH = 8;

  public static ObjectNode translate(Schema<?> schema) {
    return translate(schema, 0);
  }

  @SuppressWarnings("rawtypes")
  private static ObjectNode translate(Schema<?> schema, int depth) {
    ObjectNode node = Json.obj();
    if (schema == null || depth > MAX_DEPTH) {
      node.put("type", "string");
      return node;
    }

    String type = schema.getType();
    if (type == null && schema.getProperties() != null) type = "object";
    if (type == null && schema.getItems() != null) type = "array";
    node.put("type", type == null ? "string" : type);

    if (schema.getDescription() != null) node.put("description", trim(schema.getDescription()));
    if (schema.getFormat() != null) node.put("format", schema.getFormat());

    if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
      ArrayNode allowed = node.putArray("enum");
      for (Object value : schema.getEnum()) allowed.add(String.valueOf(value));
    }
    if (schema.getItems() != null) {
      node.set("items", translate(schema.getItems(), depth + 1));
    }
    Map<String, Schema> properties = schema.getProperties();
    if (properties != null && !properties.isEmpty()) {
      ObjectNode props = node.putObject("properties");
      properties.forEach((name, sub) -> props.set(name, translate(sub, depth + 1)));
      if (schema.getRequired() != null && !schema.getRequired().isEmpty()) {
        ArrayNode required = node.putArray("required");
        schema.getRequired().forEach(required::add);
      }
    }
    return node;
  }

  /** Descriptions come from a third-party document; keep them short and treat them as labels. */
  static String trim(String description) {
    String single = description.replaceAll("\\s+", " ").trim();
    return single.length() <= 300 ? single : single.substring(0, 300) + "...";
  }

  public static boolean isTruthy(JsonNode node) {
    return node != null && !node.isNull() && node.asBoolean(false);
  }
}
