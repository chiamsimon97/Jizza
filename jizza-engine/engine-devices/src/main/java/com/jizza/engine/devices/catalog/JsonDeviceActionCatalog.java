package com.jizza.engine.devices.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.device.DeviceActionCatalog;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The action catalog, read once from {@code device-actions.json} on the classpath, as public
 * functions are seeded from one file. Adding an action for a new app is an entry in that file and
 * nothing else on the server.
 */
@Component
public class JsonDeviceActionCatalog implements DeviceActionCatalog {

  static final String RESOURCE = "/device-actions.json";

  private final List<DeviceActionSpec> actions;

  public JsonDeviceActionCatalog() {
    this.actions = load();
  }

  @Override
  public List<DeviceActionSpec> all() {
    return actions;
  }

  @Override
  public Optional<DeviceActionSpec> find(String action, int version) {
    if (action == null) return Optional.empty();
    return actions.stream()
        .filter(a -> a.action().equals(action) && (version <= 0 || a.version() == version))
        .max(Comparator.comparingInt(DeviceActionSpec::version));
  }

  static List<DeviceActionSpec> load() {
    try (InputStream in = JsonDeviceActionCatalog.class.getResourceAsStream(RESOURCE)) {
      if (in == null) throw new IllegalStateException(RESOURCE + " is missing");
      JsonNode root = Json.mapper().readTree(in);
      List<DeviceActionSpec> out = new ArrayList<>();
      for (JsonNode a : root.path("actions")) {
        List<String> conditions = new ArrayList<>();
        a.path("conditions").forEach(c -> conditions.add(c.asText()));
        List<String> platforms = new ArrayList<>();
        a.path("platforms").forEach(p -> platforms.add(p.asText()));
        out.add(new DeviceActionSpec(a.path("action").asText(), a.path("version").asInt(1),
            a.path("capability").asText(),
            ActionType.valueOf(a.path("actionType").asText("MONEY").toUpperCase(Locale.ROOT)),
            a.path("title").asText(), a.path("description").asText(null),
            resolveRefs(a.get("input"), root), resolveRefs(a.get("output"), root),
            List.copyOf(conditions), List.copyOf(platforms),
            a.path("defaultTimeoutSec").asInt(120)));
      }
      return List.copyOf(out);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read " + RESOURCE, e);
    }
  }

  private static JsonNode resolveRefs(JsonNode node, JsonNode root) {
      if (node == null) return null;
      if (node.isObject() && node.has("$ref")) {
        String ref = node.path("$ref").asText();
        String prefix = "device-actions.json#/schemas/";
        if (!ref.startsWith(prefix)) throw new IllegalStateException("Unsupported catalog ref " + ref);
        JsonNode resolved = root.path("schemas").path(ref.substring(prefix.length()));
        if (resolved.isMissingNode()) throw new IllegalStateException("Missing catalog ref " + ref);
        return resolveRefs(resolved, root);
      }
      if (node.isObject()) {
        ObjectNode copy = Json.mapper().createObjectNode();
        node.fields().forEachRemaining(field ->
            copy.set(field.getKey(), resolveRefs(field.getValue(), root)));
        return copy;
      }
      if (node.isArray()) {
        ArrayNode copy = Json.mapper().createArrayNode();
        node.forEach(value -> copy.add(resolveRefs(value, root)));
        return copy;
      }
      return node.deepCopy();
  }
}
