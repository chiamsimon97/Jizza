package com.jizza.engine.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.regex.Pattern;

/**
 * Settings of a run's steps replaced for that run only, kept in the run's context as
 * {@code {"nodeId": [{"path": ["arguments", "q"], "value": "{{ input.q }}"}]}}.
 *
 * <p>A form made from a workflow's steps fills those steps' settings with what was submitted. The
 * override never holds the submitted text itself: it points the setting at the run's input
 * ({@code {{ input.field }}}, or {@code input.field} in a formula), and the input is rendered as
 * data, so nothing a stranger types into a public form is read as a formula.
 */
public final class StepOverrides {

  /** A setting's path: keys and list positions, at most four deep. */
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
  /** What an override may set: a reference to one input field, as a template or a formula. */
  private static final Pattern VALUE =
      Pattern.compile("(\\{\\{ ?input\\.[A-Za-z_][A-Za-z0-9_]{0,63} ?}}|input\\.[A-Za-z_][A-Za-z0-9_]{0,63})");
  private static final int MAX_DEPTH = 4;

  private StepOverrides() {}

  /** Whether {@code value} is something an override may set. */
  public static boolean allowedValue(String value) {
    return value != null && VALUE.matcher(value).matches();
  }

  /** Whether {@code path} can be overridden. The note, retry and the step's kind cannot. */
  public static boolean allowedPath(JsonNode path) {
    if (path == null || !path.isArray() || path.isEmpty() || path.size() > MAX_DEPTH) return false;
    for (JsonNode part : path) {
      if (part.isInt()) {
        if (part.asInt() < 0 || part.asInt() > 50) return false;
      } else if (!part.isTextual() || !KEY.matcher(part.asText()).matches()) {
        return false;
      }
    }
    String head = path.get(0).asText("");
    return !head.equals("retry") && !head.equals("note") && !head.equals("preset");
  }

  /** {@code node} with the overrides for it applied, or {@code node} itself when there are none. */
  public static WorkflowNode apply(WorkflowNode node, JsonNode overrides) {
    if (overrides == null || !overrides.isObject()) return node;
    JsonNode mine = overrides.path(node.id());
    if (!mine.isArray() || mine.isEmpty()) return node;
    ObjectNode config = node.config() != null && node.config().isObject()
        ? ((ObjectNode) node.config()).deepCopy() : Json.obj();
    boolean changed = false;
    for (JsonNode o : mine) {
      JsonNode path = o.path("path");
      String value = o.path("value").asText(null);
      if (!allowedPath(path) || !allowedValue(value)) continue;
      if (set(config, path, value)) changed = true;
    }
    return changed ? new WorkflowNode(node.id(), node.type(), node.label(), config, node.retry())
        : node;
  }

  private static boolean set(ObjectNode config, JsonNode path, String value) {
    JsonNode at = config;
    for (int i = 0; i < path.size() - 1; i++) {
      JsonNode part = path.get(i);
      JsonNode nextPart = path.get(i + 1);
      JsonNode child = part.isInt() ? (at.isArray() ? at.get(part.asInt()) : null) : at.get(part.asText());
      if (child == null || child.isNull() || !child.isContainerNode()) {
        if (part.isInt()) return false; // a list position must already be there
        child = nextPart.isInt() ? ((ObjectNode) at).putArray(part.asText())
            : ((ObjectNode) at).putObject(part.asText());
      }
      at = child;
    }
    JsonNode last = path.get(path.size() - 1);
    if (last.isInt()) {
      if (!at.isArray() || last.asInt() >= at.size()) return false;
      ((ArrayNode) at).set(last.asInt(), value);
    } else {
      if (!at.isObject()) return false;
      ((ObjectNode) at).put(last.asText(), value);
    }
    return true;
  }
}
