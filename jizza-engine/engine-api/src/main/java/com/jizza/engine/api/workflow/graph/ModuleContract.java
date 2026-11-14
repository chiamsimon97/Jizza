package com.jizza.engine.api.workflow.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a workflow takes and gives when it is used as a module: a step of another workflow, like a
 * function with named parameters and results.
 *
 * <p>The inputs are declared on the start step ({@code TRIGGER} config {@code inputs}) and each run
 * reads them as {@code input.<name>}. The outputs are declared on the end steps ({@code END} config
 * {@code outputs}), each with a value mapped from earlier steps, and a step that runs the module
 * reads them as {@code nodes.<step>.<name>}. A workflow that declares neither works as before.
 */
public final class ModuleContract {

  public static final int MAX_FIELDS = 30;
  public static final Set<String> TYPES = Set.of("text", "number", "boolean", "date", "datetime", "list", "map",
      "any");
  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

  private ModuleContract() {}

  /** Whether {@code name} can name an input or output: letters, digits and _, starting with a letter. */
  public static boolean isName(String name) {
    return name != null && NAME.matcher(name).matches();
  }

  /**
   * One input or output.
   *
   * @param value        for an output, what it gives: text with {@code {{ … }}} placeholders
   * @param defaultValue for an input, used when a run is not given it
   * @param format       for a date or date and time, how the site it fills writes it (DD/MM/YYYY);
   *                     the run may give it in any form and the step writes it that way
   */
  public record Field(String name, String type, boolean required, String description,
                      JsonNode defaultValue, String value, String format) {

    public Field(String name, String type, boolean required, String description,
                 JsonNode defaultValue, String value) {
      this(name, type, required, description, defaultValue, value, null);
    }
  }

  /** The inputs the start step declares, in order; empty when it declares none. */
  public static List<Field> inputsOf(WorkflowGraph graph) {
    WorkflowNode trigger = graph.nodes().stream().filter(n -> n.type() == NodeType.TRIGGER)
        .findFirst().orElse(null);
    return trigger == null ? List.of() : fields(trigger.config(), "inputs");
  }

  /** The outputs the end steps declare, by name; where end steps share a name, the first wins. */
  public static List<Field> outputsOf(WorkflowGraph graph) {
    Map<String, Field> out = new LinkedHashMap<>();
    for (WorkflowNode node : graph.nodes()) {
      if (node.type() != NodeType.END) continue;
      for (Field f : fields(node.config(), "outputs")) out.putIfAbsent(f.name(), f);
    }
    return List.copyOf(out.values());
  }

  public static List<Field> fields(JsonNode config, String key) {
    JsonNode list = config == null ? null : config.get(key);
    if (list == null || !list.isArray()) return List.of();
    List<Field> out = new ArrayList<>();
    for (JsonNode f : list) {
      String type = f.path("type").asText("any");
      out.add(new Field(f.path("name").asText("").strip(), TYPES.contains(type) ? type : "any",
          f.path("required").asBoolean(false), f.path("description").asText(null),
          f.hasNonNull("default") ? f.get("default") : null, f.path("value").asText(null),
          f.hasNonNull("format") ? f.get("format").asText() : null));
    }
    return out;
  }

  /** Checks the declared inputs and outputs: names, types, no repeats. */
  public static void validate(WorkflowGraph graph) {
    for (WorkflowNode node : graph.nodes()) {
      List<String> keys = switch (node.type()) {
        case TRIGGER -> List.of("inputs");
        case END -> List.of("outputs");
        // A step that runs a saved function maps its inputs by name; the function declares them.
        case SCRIPT -> node.config() != null && node.config().hasNonNull("functionId")
            ? List.<String>of() : List.of("inputs", "outputs");
        default -> List.of();
      };
      for (String key : keys) validateFields(node, key);
    }
  }

  private static void validateFields(WorkflowNode node, String key) {
    JsonNode raw = node.config() == null ? null : node.config().get(key);
    if (raw == null || raw.isNull()) return;
    if (!raw.isArray()) throw new ValidationException("Step '" + node.id() + "' has " + key + " that are not a list");
    if (raw.size() > MAX_FIELDS) {
      throw new ValidationException("Step '" + node.id() + "' has more than " + MAX_FIELDS + " " + key);
    }
    Set<String> seen = new HashSet<>();
    String what = key.equals("inputs") ? "input" : "output";
    for (Field f : fields(node.config(), key)) {
      if (!NAME.matcher(f.name()).matches()) {
        throw new ValidationException("The " + what + " named '" + f.name() + "' needs a name of letters, "
            + "digits and _, starting with a letter");
      }
      if (!seen.add(f.name())) throw new ValidationException("There are two " + what + "s named '" + f.name() + "'");
      if (node.type() == NodeType.END && (f.value() == null || f.value().isBlank())) {
        throw new ValidationException("The output '" + f.name() + "' needs a value");
      }
    }
  }

  /**
   * The run's input as the workflow declares it: defaults filled in, text read as numbers, true/false
   * and lists where the input says so. Undeclared fields pass through untouched.
   *
   * @throws ValidationException naming every required input that is missing, or one of the wrong type
   */
  public static JsonNode prepareInput(WorkflowGraph graph, JsonNode input) {
    List<Field> inputs = inputsOf(graph);
    if (inputs.isEmpty()) return input;
    if (input != null && !input.isNull() && !input.isObject()) {
      throw new ValidationException("This workflow takes its input as named fields: "
          + String.join(", ", inputs.stream().map(Field::name).toList()));
    }
    ObjectNode out = input == null || input.isNull() ? Json.obj() : ((ObjectNode) input).deepCopy();
    List<String> missing = new ArrayList<>();
    for (Field f : inputs) {
      JsonNode value = out.get(f.name());
      if (blank(value) && f.defaultValue() != null) value = f.defaultValue();
      if (blank(value)) {
        if (f.required()) missing.add(f.name());
        continue;
      }
      JsonNode typed = coerce(value, f.type());
      if (typed == null) {
        throw new ValidationException("The input '" + f.name() + "' should be " + typeName(f.type())
            + ", not " + Json.toNode(value).toString());
      }
      out.set(f.name(), typed);
    }
    if (!missing.isEmpty()) {
      throw new ValidationException("This workflow needs " + (missing.size() == 1 ? "the input " : "the inputs ")
          + String.join(", ", missing));
    }
    return out;
  }

  /** The declared inputs as a JSON schema, which is what a workflow's input schema holds; null when none. */
  public static JsonNode inputSchema(WorkflowGraph graph) {
    List<Field> inputs = inputsOf(graph);
    if (inputs.isEmpty()) return null;
    ObjectNode schema = Json.obj().put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    ArrayNode required = schema.putArray("required");
    for (Field f : inputs) {
      ObjectNode p = properties.putObject(f.name());
      String jsonType = switch (f.type()) {
        case "number" -> "number";
        case "boolean" -> "boolean";
        case "list" -> "array";
        case "map" -> "object";
        case "text", "date", "datetime" -> "string";
        default -> null;
      };
      if (jsonType != null) p.put("type", jsonType);
      if (f.type().equals("date")) p.put("format", "date-time");
      if (f.type().equals("datetime")) p.put("format", "date-time");
      // How the site writes it, for whoever fills the input in.
      if (f.format() != null && !f.format().isBlank()) p.put("x-date-format", f.format());
      if (f.description() != null && !f.description().isBlank()) p.put("description", f.description());
      if (f.defaultValue() != null) p.set("default", f.defaultValue());
      if (f.required()) required.add(f.name());
    }
    return schema;
  }

  private static boolean blank(JsonNode value) {
    return value == null || value.isNull() || value.isMissingNode() || (value.isTextual() && value.asText().isBlank());
  }

  /** The value as the type, reading text where it can; null when it cannot be that type. */
  public static JsonNode coerce(JsonNode value, String type) {
    String text = value.isTextual() ? value.asText().strip() : null;
    switch (type) {
      case "number":
        if (value.isNumber()) return value;
        if (text != null) {
          try {
            return Json.toNode(new java.math.BigDecimal(text.replace(",", "")));
          } catch (NumberFormatException e) {
            return null;
          }
        }
        return null;
      case "boolean":
        if (value.isBoolean()) return value;
        if ("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)) return Json.toNode(true);
        if ("false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text)) return Json.toNode(false);
        return null;
      case "list":
        if (value.isArray()) return value;
        return text != null && text.startsWith("[") ? parsed(text, true) : null;
      case "map":
        if (value.isObject()) return value;
        return text != null && text.startsWith("{") ? parsed(text, false) : null;
      case "text", "date", "datetime":
        return value.isValueNode() ? Json.toNode(value.asText()) : null;
      default:
        return value;
    }
  }

  private static JsonNode parsed(String text, boolean array) {
    try {
      JsonNode node = Json.mapper().readTree(text);
      return array == node.isArray() && (array || node.isObject()) ? node : null;
    } catch (Exception e) {
      return null;
    }
  }

  public static String typeName(String type) {
    return switch (type) {
      case "number" -> "a number";
      case "boolean" -> "true or false";
      case "list" -> "a list";
      case "map" -> "a set of fields";
      case "date" -> "a date";
      case "datetime" -> "a date and time";
      default -> "text";
    };
  }
}
