package com.jizza.engine.api.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Makes a tool's input schema acceptable to the model provider, whatever produced it (an MCP
 * server, a paired device, a generated API tool, a workflow module). The provider wants a plain
 * object at the top: {@code "type": "object"}, and no {@code oneOf}, {@code anyOf}, {@code allOf},
 * {@code not} or {@code if}/{@code then}/{@code else} there. Nested combinators are fine and kept.
 *
 * <p>Top-level combinators are flattened into one object: every branch's properties are offered;
 * {@code allOf} keeps every branch's required fields, while {@code oneOf}/{@code anyOf} keep only
 * the fields every branch requires and say the choices in the description. Only what is sent to
 * the model changes: calls are still checked against the tool's own schema.
 */
public final class ToolInputSchemas {

  private ToolInputSchemas() {}

  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final List<String> COMBINATORS = List.of("allOf", "anyOf", "oneOf");
  private static final List<String> DROPPED = List.of("not", "if", "then", "else");
  private static final int MAX_DEPTH = 5;

  /** The schema to send, or the same node when it already fits. Never returns null. */
  public static JsonNode forModel(JsonNode schema) {
    if (!needsChange(schema)) return schema;
    ObjectNode root = schema != null && schema.isObject() ? ((ObjectNode) schema).deepCopy()
        : F.objectNode();
    flatten(root, root, 0);
    DROPPED.forEach(root::remove);
    root.put("type", "object");
    if (!root.path("properties").isObject()) root.putObject("properties");
    return root;
  }

  /** Whether {@link #forModel} would change the schema. */
  public static boolean needsChange(JsonNode schema) {
    if (schema == null || !schema.isObject()) return true;
    if (!"object".equals(schema.path("type").asText(null))) return true;
    if (schema.has("properties") && !schema.get("properties").isObject()) return true;
    for (String key : COMBINATORS) if (schema.has(key)) return true;
    for (String key : DROPPED) if (schema.has(key)) return true;
    return false;
  }

  private static void flatten(ObjectNode target, ObjectNode root, int depth) {
    for (String key : COMBINATORS) {
      JsonNode branches = target.remove(key);
      if (branches == null || !branches.isArray() || depth >= MAX_DEPTH) continue;
      List<ObjectNode> objects = new ArrayList<>();
      for (JsonNode branch : branches) {
        JsonNode resolved = resolve(branch, root);
        if (!resolved.isObject()) continue;
        ObjectNode copy = ((ObjectNode) resolved).deepCopy();
        flatten(copy, root, depth + 1);
        // A branch that is not about an object (say a bare string) has nothing to offer here.
        String type = copy.path("type").asText("object");
        if (!"object".equals(type) && !copy.has("properties")) continue;
        objects.add(copy);
      }
      if (objects.isEmpty()) continue;

      ObjectNode props = target.path("properties").isObject()
          ? (ObjectNode) target.get("properties") : target.putObject("properties");
      for (ObjectNode branch : objects) {
        branch.path("properties").fields().forEachRemaining(f -> {
          if (!props.has(f.getKey())) props.set(f.getKey(), f.getValue());
        });
      }

      Set<String> required = names(target.get("required"));
      if ("allOf".equals(key)) {
        objects.forEach(b -> required.addAll(names(b.get("required"))));
        if (!target.has("description")) {
          objects.stream().map(b -> b.path("description").asText(""))
              .filter(d -> !d.isBlank()).findFirst()
              .ifPresent(d -> target.put("description", d));
        }
      } else {
        Set<String> common = null;
        StringJoiner choices = new StringJoiner("; or ");
        boolean differ = false;
        for (ObjectNode b : objects) {
          Set<String> own = names(b.get("required"));
          if (common == null) common = new LinkedHashSet<>(own);
          else {
            if (!common.equals(own)) differ = true;
            common.retainAll(own);
          }
          String label = b.path("title").asText(b.path("description").asText(""));
          String fields = own.isEmpty() ? "" : String.join(" + ", own);
          String choice = label.isBlank() ? fields
              : fields.isBlank() ? label : label + " (" + fields + ")";
          if (!choice.isBlank()) choices.add(choice);
        }
        if (common != null) required.addAll(common);
        if ((differ || objects.size() > 1) && choices.length() > 0) {
          String note = "Give one of these: " + choices + ".";
          String own = target.path("description").asText("");
          target.put("description", own.isBlank() ? note : own + " " + note);
        }
      }
      // Only fields that are offered can be required.
      required.retainAll(names(props));
      if (required.isEmpty()) target.remove("required");
      else {
        ArrayNode array = target.putArray("required");
        required.forEach(array::add);
      }
    }
  }

  /** Follows a local {@code $ref} ({@code #/$defs/X} or {@code #/definitions/X}). */
  private static JsonNode resolve(JsonNode branch, ObjectNode root) {
    JsonNode current = branch;
    for (int hops = 0; hops < MAX_DEPTH && current.isObject() && current.has("$ref"); hops++) {
      String ref = current.path("$ref").asText("");
      if (!ref.startsWith("#/")) return current;
      JsonNode found = root.at(ref.substring(1));
      if (found.isMissingNode()) return current;
      current = found;
    }
    return current;
  }

  private static Set<String> names(JsonNode node) {
    Set<String> out = new LinkedHashSet<>();
    if (node == null) return out;
    if (node.isArray()) node.forEach(n -> { if (n.isTextual()) out.add(n.asText()); });
    else if (node.isObject()) node.fieldNames().forEachRemaining(out::add);
    return out;
  }
}
