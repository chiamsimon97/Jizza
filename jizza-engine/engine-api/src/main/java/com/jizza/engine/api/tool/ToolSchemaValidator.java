package com.jizza.engine.api.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Minimal, dependency-free JSON Schema check applied to <em>every</em> tool call before it is
 * executed (R16). Deliberately conservative: it understands the subset of JSON Schema that the
 * tool generator and the built-in tools emit. Catalog loaders must resolve external references
 * before calling this validator.
 */
public final class ToolSchemaValidator {

  private ToolSchemaValidator() {}

  public record Result(boolean valid, List<String> errors) {
    public String message() { return String.join("; ", errors); }
  }

  public static Result validate(JsonNode schema, JsonNode value) {
    List<String> errors = new ArrayList<>();
    check(schema, value, "$", errors);
    return new Result(errors.isEmpty(), errors);
  }

  private static void check(JsonNode schema, JsonNode value, String path, List<String> errors) {
    if (schema == null || !schema.isObject()) return;
    if (value == null) value = com.fasterxml.jackson.databind.node.NullNode.instance;

    for (String composition : List.of("allOf", "anyOf", "oneOf")) {
      JsonNode choices = schema.get(composition);
      if (choices == null || !choices.isArray()) continue;
      int matches = 0;
      for (JsonNode choice : choices) {
        List<String> choiceErrors = new ArrayList<>();
        check(choice, value, path, choiceErrors);
        if (choiceErrors.isEmpty()) matches++;
      }
      if ("allOf".equals(composition) && matches != choices.size()) {
        errors.add(path + ": does not satisfy allOf");
      } else if ("anyOf".equals(composition) && matches == 0) {
        errors.add(path + ": does not satisfy anyOf");
      } else if ("oneOf".equals(composition) && matches != 1) {
        errors.add(path + ": does not satisfy exactly one oneOf option");
      }
    }

    JsonNode typeNode = schema.get("type");
    if (typeNode != null && typeNode.isTextual()) {
      String type = typeNode.asText();
      if (!typeMatches(type, value)) {
        errors.add(path + ": expected " + type + " but got " + value.getNodeType().name().toLowerCase());
        return;
      }
    }

    JsonNode enumNode = schema.get("enum");
    if (enumNode != null && enumNode.isArray()) {
      boolean found = false;
      for (JsonNode allowed : enumNode) if (allowed.equals(value)) { found = true; break; }
      if (!found) errors.add(path + ": value not permitted by enum");
    }

    if (value.isObject()) {
      JsonNode required = schema.get("required");
      if (required != null && required.isArray()) {
        for (JsonNode r : required) {
          if (!value.has(r.asText())) errors.add(path + ": missing required property '" + r.asText() + "'");
        }
      }
      JsonNode props = schema.get("properties");
      if (props != null && props.isObject()) {
        var names = value.fieldNames();
        while (names.hasNext()) {
          String n = names.next();
          JsonNode sub = props.get(n);
          if (sub != null) {
            check(sub, value.get(n), path + "." + n, errors);
          } else if (schema.path("additionalProperties").isBoolean()
              && !schema.path("additionalProperties").asBoolean()) {
            errors.add(path + ": unexpected property '" + n + "'");
          }
        }
      }
    }

    if (value.isArray()) {
      if (schema.has("minItems") && value.size() < schema.get("minItems").asInt())
        errors.add(path + ": fewer than minItems");
      if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt())
        errors.add(path + ": more than maxItems");
      if (schema.path("uniqueItems").asBoolean(false)) {
        HashSet<JsonNode> seen = new HashSet<>();
        for (JsonNode item : value) {
          if (!seen.add(item)) {
            errors.add(path + ": duplicate item");
            break;
          }
        }
      }
      JsonNode items = schema.get("items");
      if (items != null) {
        for (int i = 0; i < value.size(); i++) check(items, value.get(i), path + "[" + i + "]", errors);
      }
    }

    if (value.isNumber()) {
      if (schema.has("minimum") && value.asDouble() < schema.get("minimum").asDouble())
        errors.add(path + ": below minimum");
      if (schema.has("maximum") && value.asDouble() > schema.get("maximum").asDouble())
        errors.add(path + ": above maximum");
    }

    if (value.isTextual()) {
      if (schema.has("minLength") && value.asText().length() < schema.get("minLength").asInt())
        errors.add(path + ": shorter than minLength");
      if (schema.has("maxLength") && value.asText().length() > schema.get("maxLength").asInt())
        errors.add(path + ": longer than maxLength");
      if (schema.has("pattern")) {
        try {
          if (!Pattern.compile(schema.get("pattern").asText()).matcher(value.asText()).find())
            errors.add(path + ": does not match pattern");
        } catch (PatternSyntaxException e) {
          errors.add(path + ": schema has an invalid pattern");
        }
      }
    }
  }

  private static boolean typeMatches(String type, JsonNode value) {
    return switch (type) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isTextual();
      case "integer" -> value.isIntegralNumber();
      case "number" -> value.isNumber();
      case "boolean" -> value.isBoolean();
      case "null" -> value.isNull();
      default -> true;
    };
  }
}
