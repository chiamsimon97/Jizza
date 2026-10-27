package com.jizza.engine.api.device;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Which device a command is for: one by id, any of the person's devices with a tag, any that
 * declares a capability, or their phone. With none set, any of their devices that can run the
 * action will do.
 */
public record DeviceTarget(UUID deviceId, String tag, String capability, boolean phone) {

  public static DeviceTarget any() {
    return new DeviceTarget(null, null, null, false);
  }

  public static DeviceTarget device(UUID deviceId) {
    return new DeviceTarget(deviceId, null, null, false);
  }

  /** Reads a step's {@code target}: an id string, or an object with one of the fields. */
  public static DeviceTarget from(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) return any();
    if (node.isTextual()) {
      String text = node.asText().trim();
      if (text.isEmpty() || "any".equals(text)) return any();
      if ("phone".equals(text)) return new DeviceTarget(null, null, null, true);
      return device(UUID.fromString(text));
    }
    UUID id = node.hasNonNull("deviceId") && !node.get("deviceId").asText().isBlank()
        ? UUID.fromString(node.get("deviceId").asText()) : null;
    String tag = node.hasNonNull("tag") && !node.get("tag").asText().isBlank()
        ? node.get("tag").asText() : null;
    String capability = node.hasNonNull("capability")
        && !node.get("capability").asText().isBlank() ? node.get("capability").asText() : null;
    return new DeviceTarget(id, tag, capability, node.path("phone").asBoolean(false));
  }

  public String describe() {
    if (deviceId != null) return "that device";
    if (phone) return "your phone";
    if (tag != null) return "a device tagged " + tag;
    if (capability != null) return "a device with " + capability;
    return "any of your devices";
  }
}
