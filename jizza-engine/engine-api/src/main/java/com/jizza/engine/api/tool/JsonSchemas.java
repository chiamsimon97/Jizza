package com.jizza.engine.api.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny helpers for building and checking the JSON Schemas exposed as tool inputs. */
public final class JsonSchemas {

  private JsonSchemas() {}

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  public static ObjectNode object(Map<String, JsonNode> props, List<String> required) {
    ObjectNode schema = F.objectNode();
    schema.put("type", "object");
    ObjectNode p = schema.putObject("properties");
    props.forEach(p::set);
    ArrayNode req = schema.putArray("required");
    required.forEach(req::add);
    schema.put("additionalProperties", false);
    return schema;
  }

  public static ObjectNode string(String description) {
    ObjectNode n = F.objectNode();
    n.put("type", "string");
    if (description != null) n.put("description", description);
    return n;
  }

  public static ObjectNode integer(String description) {
    ObjectNode n = F.objectNode();
    n.put("type", "integer");
    if (description != null) n.put("description", description);
    return n;
  }

  public static ObjectNode bool(String description) {
    ObjectNode n = F.objectNode();
    n.put("type", "boolean");
    if (description != null) n.put("description", description);
    return n;
  }

  public static ObjectNode objectSchema(String description) {
    ObjectNode n = F.objectNode();
    n.put("type", "object");
    if (description != null) n.put("description", description);
    return n;
  }

  public static ObjectNode emptyObject() {
    return object(new LinkedHashMap<>(), List.of());
  }
}
