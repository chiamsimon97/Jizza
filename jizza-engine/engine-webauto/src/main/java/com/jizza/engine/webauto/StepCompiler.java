package com.jizza.engine.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the reviewed steps of a recording into the graph JSON the canvas saves, so a recording
 * becomes an ordinary workflow module: a Start with the inputs the person chose, one web step per
 * kept step, and an End that returns what the recording read.
 *
 * <p>Pure on purpose: the same steps always compile to the same graph, and nothing here talks to a
 * browser, a database or a model. A step that holds a secret never carries its value; it must name
 * a saved login before it can be saved.
 */
final class StepCompiler {

  static final String OPEN = "browser.open";
  private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
  private static final Set<String> VALUE_FIELDS = Set.of("text", "value", "password", "secret");

  private StepCompiler() {}

  /**
   * @param steps the live steps in review order; each is the stored step JSON, with the person's
   *              {@code mappings} and {@code secretRef} from review
   */
  static ObjectNode compile(String startUrl, List<JsonNode> steps) {
    return compile(startUrl, steps, false);
  }

  /**
   * @param inline compile only the steps, for placing in a workflow being built: no Start or End,
   *               and a field the person made an input keeps the value that was recorded, because
   *               there is no module around it to be given one
   */
  static ObjectNode compile(String startUrl, List<JsonNode> steps, boolean inline) {
    ArrayNode nodes = Json.mapper().createArrayNode();
    ArrayNode edges = Json.mapper().createArrayNode();
    ArrayNode inputs = Json.mapper().createArrayNode();
    ArrayNode outputs = Json.mapper().createArrayNode();
    Set<String> inputNames = new HashSet<>();
    Set<String> outputNames = new HashSet<>();

    String previous = null;
    if (!inline) {
      nodes.add(node("start", "TRIGGER", "Start", Json.obj().set("inputs", inputs), 0));
      previous = "start";
    }
    int row = 1;

    if (steps.isEmpty() || !OPEN.equals(steps.get(0).path("action").asText())) {
      ObjectNode config = webConfig(OPEN, 2, Json.obj().put("url", startUrl));
      nodes.add(node("open", "DEVICE_ACTION", "Open " + startUrl, config, row++));
      if (previous != null) edges.add(edge(previous, "open"));
      previous = "open";
    }

    int index = 0;
    for (JsonNode step : steps) {
      index++;
      String id = "step_" + index;
      String action = step.path("action").asText();
      String label = step.path("label").asText(action);
      ObjectNode stepInputs = step.path("input").isObject()
          ? ((ObjectNode) step.get("input")).deepCopy() : Json.obj();
      boolean sensitive = step.path("meta").path("sensitive").asBoolean(false);
      String secretRef = step.path("secretRef").asText("");

      if (sensitive) {
        VALUE_FIELDS.forEach(stepInputs::remove);
        if (secretRef.isBlank()) {
          throw new ValidationException("Choose a saved login for the password step");
        }
      }
      if (!secretRef.isBlank()) {
        VALUE_FIELDS.forEach(stepInputs::remove);
        stepInputs.put("secretRef", secretRef);
      }

      JsonNode mappings = step.path("mappings");
      for (Iterator<Map.Entry<String, JsonNode>> it = mappings.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> mapping = it.next();
        String name = mapping.getValue().path("name").asText("");
        if (!FIELD.matcher(name).matches()) {
          throw new ValidationException("'" + name + "' cannot be an input name. Use letters, "
              + "numbers and underscores, starting with a letter");
        }
        if (inline) {
          JsonNode kept = mapping.getValue().get("default");
          if (kept != null && !kept.isNull()) stepInputs.set(mapping.getKey(), kept);
          continue;
        }
        stepInputs.put(mapping.getKey(), "{{ input." + name + " }}");
        if (inputNames.add(name)) inputs.add(inputField(name, mapping.getValue()));
      }

      int version = step.path("version").asInt(1);
      nodes.add(node(id, "DEVICE_ACTION", label, webConfig(action, version, stepInputs), row++));
      if (previous != null) edges.add(edge(previous, id));
      previous = id;

      String outputKey = readsData(action);
      if (!inline && outputKey != null) {
        String name = unique(nameFrom(label, outputKey), outputNames);
        outputs.add(Json.obj().put("name", name)
            .put("type", "rows".equals(outputKey) ? "list" : "text")
            .put("value", "{{ nodes." + id + "." + outputKey + " }}"));
      }
    }

    if (!inline) {
      nodes.add(node("end", "END", "End", Json.obj().set("outputs", outputs), row));
      edges.add(edge(previous, "end"));
    }
    ObjectNode graph = Json.obj();
    graph.set("nodes", nodes);
    graph.set("edges", edges);
    return graph;
  }

  /** The mapped inputs of the steps, in the shape a workflow keeps as its input schema. */
  static ArrayNode inputsOf(ObjectNode graph) {
    for (JsonNode n : graph.path("nodes")) {
      if ("TRIGGER".equals(n.path("type").asText())) return (ArrayNode) n.path("config").path("inputs");
    }
    return Json.mapper().createArrayNode();
  }

  private static String readsData(String action) {
    return switch (action) {
      case "browser.getText" -> "text";
      case "browser.extractTable" -> "rows";
      default -> null;
    };
  }

  private static String nameFrom(String label, String fallback) {
    String cleaned = label.replaceAll("[^A-Za-z0-9]+", " ").strip();
    StringBuilder out = new StringBuilder();
    for (String word : cleaned.split(" ")) {
      if (word.isEmpty()) continue;
      out.append(out.length() == 0 ? word.substring(0, 1).toLowerCase() : word.substring(0, 1).toUpperCase())
          .append(word.substring(1));
    }
    String name = out.toString();
    if (name.isEmpty() || !Character.isLetter(name.charAt(0))) name = fallback;
    return name.length() > 64 ? name.substring(0, 64) : name;
  }

  private static String unique(String base, Set<String> used) {
    String name = base;
    for (int i = 2; !used.add(name); i++) name = base + i;
    return name;
  }

  private static ObjectNode inputField(String name, JsonNode mapping) {
    ObjectNode field = Json.obj().put("name", name)
        .put("type", mapping.path("type").asText("text"));
    JsonNode def = mapping.get("default");
    boolean hasDefault = def != null && !def.isNull();
    field.put("required", !hasDefault);
    if (hasDefault) field.set("default", def);
    return field;
  }

  private static ObjectNode webConfig(String action, int version, ObjectNode inputs) {
    ObjectNode config = Json.obj().put("preset", "web").put("action", action)
        .put("target", "sameBrowser");
    config.put("version", version);
    config.set("inputs", inputs);
    return config;
  }

  private static ObjectNode node(String id, String type, String label, ObjectNode config, int row) {
    ObjectNode node = Json.obj().put("id", id).put("type", type).put("label", label);
    node.set("config", config);
    return node.put("x", 300).put("y", 60 + row * 140);
  }

  private static ObjectNode edge(String from, String to) {
    return Json.obj().put("from", from).put("to", to);
  }
}
