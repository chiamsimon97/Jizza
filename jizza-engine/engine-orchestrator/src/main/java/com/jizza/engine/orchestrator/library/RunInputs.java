package com.jizza.engine.orchestrator.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.InputRules;
import com.jizza.engine.api.workflow.graph.ModuleContract;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The inputs a saved workflow takes, as the chat's start card and the input reader see them: the
 * start step's declarations with their catalogue details (label, group, choices and synonyms, rows
 * and their columns, when to ask), or the reuse profile's plain names for a workflow that declares
 * none. Values pulled out of a message are checked here with the same rules a run applies, so the
 * card shows a problem next to the field rather than the run failing later.
 */
final class RunInputs {

  /** How many inputs the card and the reader are told about. */
  static final int MAX = ModuleContract.MAX_FIELDS;

  private RunInputs() {}

  /**
   * One input.
   *
   * @param declaration the start step's declaration as stored (rules and all); an object with only
   *                    a name and description for an input known from the reuse profile
   */
  record Input(String name, String label, String type, boolean required, String description,
               String ask, JsonNode declaration) {

    /** Asked on the step's own card while the run is going, not needed to start it. */
    boolean atStep() {
      return "atStep".equals(ask);
    }

    List<String> options() {
      List<String> out = new ArrayList<>();
      declaration.path("options").forEach(o -> {
        if (o.isValueNode()) out.add(o.asText());
      });
      return out;
    }
  }

  /** The workflow's inputs, from its steps when they declare them, else from its profile. */
  static List<Input> of(JsonNode graph, ReuseProfile profile) {
    ArrayNode declared = declared(graph);
    List<Input> out = new ArrayList<>();
    if (declared != null && !declared.isEmpty()) {
      for (JsonNode f : declared) {
        String name = f.path("name").asText("").strip();
        if (name.isEmpty() || out.size() >= MAX) continue;
        String type = f.path("type").asText("any");
        out.add(new Input(name, f.path("label").asText(name), ModuleContract.TYPES.contains(type) ? type : "any",
            f.path("required").asBoolean(false), f.path("description").asText(null),
            f.path("ask").asText(null), f));
      }
      return out;
    }
    if (profile == null) return out;
    for (ReuseProfile.Input i : profile.inputs()) {
      if (out.size() >= MAX) break;
      ObjectNode declaration = Json.obj().put("name", i.name());
      if (i.description() != null) declaration.put("description", i.description());
      out.add(new Input(i.name(), i.name(), "text", i.required(), i.description(), null, declaration));
    }
    return out;
  }

  private static ArrayNode declared(JsonNode graph) {
    if (graph == null) return null;
    for (JsonNode node : graph.path("nodes")) {
      if ("TRIGGER".equals(node.path("type").asText()) && node.path("config").path("inputs").isArray()) {
        return (ArrayNode) node.path("config").path("inputs");
      }
    }
    return null;
  }

  /** Whether any step of the graph works in a browser, so the card asks which browser runs it. */
  static boolean usesBrowser(JsonNode graph) {
    if (graph == null) return false;
    for (JsonNode node : graph.path("nodes")) {
      JsonNode config = node.path("config");
      if ("web".equals(config.path("preset").asText())
          || config.path("action").asText("").startsWith("browser.")) {
        return true;
      }
    }
    return false;
  }

  /**
   * The inputs described for the reader, one per line, with what it needs to fill each well: the
   * type, whether it is needed, the choices (and the words that mean them) and a row's columns.
   */
  static String describe(List<Input> inputs) {
    StringBuilder text = new StringBuilder();
    for (Input i : inputs) {
      text.append("  - ").append(i.name()).append(" (").append(typeWords(i));
      if (i.required()) text.append(", needed");
      text.append(')');
      if (!i.label().equals(i.name())) text.append(" \"").append(clip(i.label(), 60)).append('"');
      if (i.description() != null && !i.description().isBlank()) {
        text.append(": ").append(clip(i.description(), 120));
      }
      List<String> options = i.options();
      if (!options.isEmpty()) {
        text.append("; one of: ").append(String.join(" | ", options.subList(0, Math.min(options.size(), 25))));
        JsonNode synonyms = i.declaration().path("synonyms");
        if (synonyms.isObject() && !synonyms.isEmpty()) {
          List<String> pairs = new ArrayList<>();
          synonyms.fields().forEachRemaining(e -> {
            if (pairs.size() < 20) pairs.add(e.getKey() + " = " + e.getValue().asText());
          });
          text.append(" (also said as: ").append(String.join(", ", pairs)).append(')');
        }
      }
      JsonNode columns = i.declaration().path("columns");
      if (columns.isArray() && !columns.isEmpty()) {
        List<String> cols = new ArrayList<>();
        for (JsonNode c : columns) {
          String key = c.path("key").asText(c.path("name").asText(""));
          if (key.isEmpty()) continue;
          StringBuilder col = new StringBuilder(key);
          List<String> colOptions = new ArrayList<>();
          c.path("options").forEach(o -> colOptions.add(o.asText()));
          if (!colOptions.isEmpty()) col.append(" [").append(String.join("|", colOptions)).append(']');
          else if (c.hasNonNull("type")) col.append(" [").append(c.get("type").asText()).append(']');
          cols.add(col.toString());
        }
        text.append("; each row has: ").append(String.join(", ", cols));
      }
      text.append('\n');
    }
    return text.toString();
  }

  private static String typeWords(Input i) {
    return switch (i.type()) {
      case "date" -> "date as YYYY-MM-DD";
      case "datetime" -> "date and time as YYYY-MM-DDTHH:MM";
      case "list" -> i.declaration().path("columns").isArray() ? "list of rows" : "list";
      case "number", "boolean", "map" -> i.type();
      default -> "text";
    };
  }

  /** What the values checked against the inputs came to. */
  record Checked(ObjectNode values, Map<String, String> problems) {}

  /**
   * The values pulled from the message, each checked against its input's type and rules. A value
   * the rules refuse is kept as it was said, with the reason, for the person to correct on the
   * card. Values for names the workflow does not take are dropped.
   */
  static Checked check(List<Input> inputs, JsonNode given) {
    ObjectNode values = Json.obj();
    Map<String, String> problems = new LinkedHashMap<>();
    if (given == null || !given.isObject()) return new Checked(values, problems);
    for (Input i : inputs) {
      JsonNode value = find(given, i.name());
      if (blank(value)) continue;
      JsonNode typed = ModuleContract.coerce(value, i.type());
      if (typed == null) {
        values.set(i.name(), value);
        problems.put(i.name(), "This should be " + typeName(i.type()));
        continue;
      }
      try {
        values.set(i.name(), InputRules.apply(i.label(), typed, i.declaration()));
      } catch (ValidationException e) {
        values.set(i.name(), typed);
        problems.put(i.name(), e.getMessage());
      }
    }
    return new Checked(values, problems);
  }

  /** The value given for {@code name}, matching the name loosely (case, spaces, _). */
  private static JsonNode find(JsonNode given, String name) {
    if (given.has(name)) return given.get(name);
    String key = fold(name);
    var it = given.fields();
    while (it.hasNext()) {
      var e = it.next();
      if (fold(e.getKey()).equals(key)) return e.getValue();
    }
    return null;
  }

  static boolean blank(JsonNode value) {
    return value == null || value.isNull() || value.isMissingNode()
        || value.isTextual() && value.asText().isBlank()
        || (value.isArray() || value.isObject()) && value.isEmpty();
  }

  private static String typeName(String type) {
    return switch (type) {
      case "number" -> "a number";
      case "boolean" -> "yes or no";
      case "list" -> "a list";
      case "date" -> "a date";
      case "datetime" -> "a date and time";
      default -> "text";
    };
  }

  private static String fold(String s) {
    return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
  }

  static String clip(String s, int max) {
    String flat = s.replaceAll("\\s+", " ").strip();
    return flat.length() <= max ? flat : flat.substring(0, max) + "…";
  }
}
