package com.jizza.engine.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.util.Iterator;
import java.util.Map;

/**
 * The steps of a recording opened for editing, as the browser gets them: each ready to run as a
 * test there, with the person's test values in place of its inputs, and a saved login named for a
 * password step. A step made in the builder ({@code flow.keep}) goes as its name only: the browser
 * cannot run it and only shows it.
 */
final class EditSteps {

  private EditSteps() {}

  static ObjectNode forBrowser(ObjectNode step, JsonNode testValues) {
    String action = step.path("action").asText();
    ObjectNode out = Json.obj().put("seq", step.path("seq").asInt()).put("action", action)
        .put("version", step.path("version").asInt(1))
        .put("label", step.path("label").asText(action));
    boolean sensitive = step.path("meta").path("sensitive").asBoolean(false);
    out.put("sensitive", sensitive);
    if (step.path("disabled").asBoolean(false)) out.put("disabled", true);
    if (step.path("optional").asBoolean(false)) out.put("optional", true);
    if (StepCompiler.KEEP.equals(action)) {
      out.set("input", Json.obj());
      out.put("kind", step.path("meta").path("kind").asText("Step"));
      return out;
    }
    ObjectNode input = step.path("input").isObject()
        ? ((ObjectNode) step.get("input")).deepCopy() : Json.obj();
    ObjectNode mapped = Json.obj();
    for (Iterator<Map.Entry<String, JsonNode>> it = step.path("mappings").fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> mapping = it.next();
      String name = mapping.getValue().path("name").asText("");
      mapped.put(mapping.getKey(), name);
      JsonNode value = testValues == null ? null : testValues.get(name);
      // An empty test value keeps what the step has (a list choice's recorded item).
      if (value != null && value.isValueNode() && !value.isNull() && !value.asText("").isBlank()) {
        set(input, mapping.getKey(), value);
      }
    }
    if (!mapped.isEmpty()) out.set("mapped", mapped);
    String secretRef = step.path("secretRef").asText("");
    if (!secretRef.isBlank()) {
      input.set("secretRef", StepCompiler.secretInput(secretRef,
          step.path("meta").path("sensitive").asBoolean(false), true));
    }
    out.set("input", input);
    if (step.path("meta").path("list").isObject()) out.set("list", step.path("meta").get("list"));
    if (step.path("meta").path("preview").isTextual()) {
      out.put("preview", step.path("meta").get("preview").asText());
    }
    return out;
  }

  private static void set(ObjectNode input, String field, JsonNode value) {
    int dot = field.lastIndexOf('.');
    JsonNode parent = dot < 0 ? input : StepCompiler.valueAt(input, field.substring(0, dot));
    String key = field.substring(dot + 1);
    if (parent instanceof ObjectNode object) {
      object.set(key, value.deepCopy());
    } else if (parent instanceof ArrayNode array && key.matches("\\d{1,3}")
        && Integer.parseInt(key) < array.size()) {
      array.set(Integer.parseInt(key), value.deepCopy());
    }
  }
}
