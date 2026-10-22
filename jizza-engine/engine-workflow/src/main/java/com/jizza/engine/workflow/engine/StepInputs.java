package com.jizza.engine.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.Templates;
import java.util.Map;

/**
 * What a step was handed, kept beside its output so the run history can show both. It is the
 * step's settings with every {@code {{ }}} placeholder filled in from the run's data, plus the
 * outputs of the steps whose paths reached it (the run's input, for the trigger).
 *
 * <p>This is a record for people, not something the engine reads back: a placeholder that fails
 * to render keeps its text and notes why, and an oversized snapshot is cut to a preview.
 */
final class StepInputs {

  /** Serialized size above which only a preview is kept. */
  static final int MAX_CHARS = 64_000;

  private final JexlSandbox sandbox;

  StepInputs(JexlSandbox sandbox) {
    this.sandbox = sandbox;
  }

  JsonNode snapshot(WorkflowNode node, RunContext context) {
    ObjectNode snapshot = Json.obj();
    Map<String, Object> variables = context.variables();
    if (node.config() != null && !node.config().isNull() && !node.config().isEmpty()) {
      JsonNode settings = render(node.config(), variables);
      if (node.type() == NodeType.HTTP_REQUEST && settings instanceof ObjectNode o) hideSecrets(o);
      snapshot.set("settings", settings);
    }
    if (node.type() == NodeType.TRIGGER) {
      snapshot.set("received", context.input());
    } else {
      ObjectNode received = Json.obj();
      for (String from : context.liveInputs(node.id())) {
        received.set(from, context.outputOf(from));
      }
      if (!received.isEmpty()) snapshot.set("received", received);
    }
    String text = snapshot.toString();
    if (text.length() <= MAX_CHARS) return snapshot;
    return Json.obj().put("truncated", true)
        .put("preview", text.substring(0, MAX_CHARS));
  }

  /** Header names whose values are credentials. */
  private static final java.util.regex.Pattern SECRET_HEADER =
      java.util.regex.Pattern.compile("(?i).*(authorization|token|secret|api[-_]?key|password|cookie).*");

  /**
   * A webhook's sign-in details and credential headers are not kept in the history, which anyone
   * looking at past runs can read; they are shown as hidden instead.
   */
  private static void hideSecrets(ObjectNode settings) {
    if (settings.path("auth") instanceof ObjectNode auth) {
      for (String field : new String[] {"password", "token", "value"}) {
        if (auth.hasNonNull(field)) auth.put(field, HIDDEN);
      }
    }
    if (settings.path("headers") instanceof ObjectNode headers) {
      headers.properties().stream().map(Map.Entry::getKey).filter(k -> SECRET_HEADER.matcher(k).matches())
          .toList().forEach(k -> headers.put(k, HIDDEN));
    }
  }

  private static final String HIDDEN = "(hidden)";

  private JsonNode render(JsonNode value, Map<String, Object> variables) {
    if (value.isTextual()) {
      String raw = value.asText();
      if (!raw.contains("{{")) return value;
      try {
        return TextNode.valueOf(Templates.render(raw, sandbox, variables));
      } catch (RuntimeException e) {
        return Json.obj().put("template", raw).put("error", String.valueOf(e.getMessage()));
      }
    }
    if (value.isObject()) {
      ObjectNode out = Json.obj();
      value.properties().forEach(e -> out.set(e.getKey(), render(e.getValue(), variables)));
      return out;
    }
    if (value.isArray()) {
      ArrayNode out = Json.mapper().createArrayNode();
      value.forEach(v -> out.add(render(v, variables)));
      return out;
    }
    return value;
  }
}
