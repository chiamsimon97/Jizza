package com.jizza.engine.devices.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.DeviceEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a device offers beyond the catalog, from its manifest: its own <b>tools</b> ({@code x-}
 * actions it describes with input and output schemas) and its <b>saved items</b> (browser
 * recordings, Shortcuts, apps) that catalog actions take by id.
 *
 * <p>A device's own tool is judged by the guardrails as the most consequential kind until its owner
 * says what it does: the app describes it, but the server cannot check that, so a tool that says it
 * only reads still counts as moving money until a person agrees. A catalog input names the kind of saved item it takes with
 * {@code "x-items": "recordings"}, so the builder can offer that device's list.
 */
public final class DeviceExtras {

  static final int MAX_TOOLS = 50;
  static final int MAX_ITEM_KINDS = 10;
  static final int MAX_ITEMS = 500;
  static final int MAX_SCHEMA_CHARS = 16 * 1024;
  static final Pattern TOOL_NAME = Pattern.compile("x-[a-z0-9-]{1,40}\\.[A-Za-z0-9_.-]{1,60}");
  static final Pattern ITEM_KIND = Pattern.compile("[a-z][a-z0-9_]{0,31}");

  private DeviceExtras() {}

  /**
   * Checks the manifest's {@code tools} and returns them cleaned, ready to keep.
   *
   * @throws ValidationException naming what is wrong, so the app's developer sees it
   */
  public static ArrayNode tools(JsonNode declared) {
    ArrayNode out = Json.mapper().createArrayNode();
    if (declared == null || declared.isMissingNode() || declared.isNull()) return out;
    if (!declared.isArray()) throw new ValidationException("A manifest's tools must be a list");
    if (declared.size() > MAX_TOOLS) {
      throw new ValidationException("A device can offer at most " + MAX_TOOLS + " tools of its own");
    }
    Set<String> seen = new HashSet<>();
    for (JsonNode t : declared) {
      String action = t.path("action").asText("");
      if (!TOOL_NAME.matcher(action).matches()) {
        throw new ValidationException("'" + action + "' is not a tool name such as x-acme.export");
      }
      int version = Math.max(1, Math.min(t.path("version").asInt(1), 9999));
      if (!seen.add(action)) throw new ValidationException("The tool " + action + " is listed twice");
      ObjectNode clean = Json.obj();
      clean.put("action", action);
      clean.put("version", version);
      clean.put("title", limit(t.path("title").asText(action), 80));
      if (t.hasNonNull("description")) clean.put("description", limit(t.get("description").asText(), 500));
      clean.set("input", schema(action, "input", t.get("input")));
      if (t.hasNonNull("output")) clean.set("output", schema(action, "output", t.get("output")));
      clean.put("timeoutSec", Math.max(1, Math.min(t.path("timeoutSec").asInt(120), 86_400)));
      out.add(clean);
    }
    return out;
  }

  /** Checks the manifest's {@code items} ({"recordings": [{"id","name","description"?}]}). */
  public static ObjectNode items(JsonNode declared) {
    ObjectNode out = Json.obj();
    if (declared == null || declared.isMissingNode() || declared.isNull()) return out;
    if (!declared.isObject()) throw new ValidationException("A manifest's items must be an object of lists");
    if (declared.size() > MAX_ITEM_KINDS) {
      throw new ValidationException("A manifest can list at most " + MAX_ITEM_KINDS + " kinds of items");
    }
    for (var kind : declared.properties()) {
      if (!ITEM_KIND.matcher(kind.getKey()).matches()) {
        throw new ValidationException("'" + kind.getKey() + "' is not an item kind such as recordings");
      }
      if (!kind.getValue().isArray()) {
        throw new ValidationException("The " + kind.getKey() + " items must be a list");
      }
      ArrayNode list = out.putArray(kind.getKey());
      for (JsonNode item : kind.getValue()) {
        if (list.size() >= MAX_ITEMS) break;
        String id = item.path("id").asText("").strip();
        if (id.isEmpty()) continue;
        ObjectNode clean = list.addObject();
        clean.put("id", limit(id, 200));
        clean.put("name", limit(item.path("name").asText(id), 200));
        if (item.hasNonNull("description")) clean.put("description", limit(item.get("description").asText(), 500));
      }
    }
    return out;
  }

  /**
   * The catalog form of a tool the device offers, when it describes {@code action}, typed as the
   * owner said (moving money until they do).
   */
  public static Optional<DeviceActionSpec> spec(DeviceEntity device, String action) {
    return spec(device.getManifest(), device.getToolTypes(), action);
  }

  public static Optional<DeviceActionSpec> spec(JsonNode manifest, JsonNode toolTypes, String action) {
    if (manifest == null || action == null) return Optional.empty();
    for (JsonNode t : manifest.path("tools")) {
      if (action.equals(t.path("action").asText())) return Optional.of(toSpec(t, toolTypes));
    }
    return Optional.empty();
  }

  /** Every tool the device describes, in catalog form. */
  public static List<DeviceActionSpec> specs(DeviceEntity device) {
    List<DeviceActionSpec> out = new ArrayList<>();
    if (device.getManifest() == null) return out;
    for (JsonNode t : device.getManifest().path("tools")) out.add(toSpec(t, device.getToolTypes()));
    return out;
  }

  static DeviceActionSpec toSpec(JsonNode t, JsonNode toolTypes) {
    String action = t.path("action").asText();
    ActionType type = ActionType.MONEY;
    String set = toolTypes == null ? null : toolTypes.path(action).asText(null);
    if (set != null) {
      try {
        type = ActionType.valueOf(set);
      } catch (IllegalArgumentException ignored) {
        // An unknown name stays the riskiest kind.
      }
    }
    return new DeviceActionSpec(action, t.path("version").asInt(1),
        action.substring(0, Math.max(0, action.indexOf('.'))), type,
        t.path("title").asText(action), t.path("description").asText("A tool this device added itself"),
        t.get("input"), t.get("output"), List.of(), List.of(), t.path("timeoutSec").asInt(120));
  }

  private static JsonNode schema(String action, String which, JsonNode schema) {
    if (schema == null || schema.isNull()) return Json.obj().put("type", "object");
    if (!schema.isObject()) {
      throw new ValidationException("The " + which + " of " + action + " must be a JSON Schema object");
    }
    if (Json.write(schema).length() > MAX_SCHEMA_CHARS) {
      throw new ValidationException("The " + which + " schema of " + action + " is larger than 16 KB");
    }
    return schema;
  }

  private static String limit(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
