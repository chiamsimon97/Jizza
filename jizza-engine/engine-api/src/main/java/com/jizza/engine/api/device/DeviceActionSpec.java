package com.jizza.engine.api.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActionType;
import java.util.List;

/**
 * One action in the catalog every device app builds to: what it needs, what it returns, and what
 * it does for the guardrails.
 *
 * @param action      dotted name, such as {@code browser.extract}; {@code x-<vendor>.*} names are
 *                    extras an app declared before the catalog knew them
 * @param capability  the capability a device must declare to be sent it
 * @param conditions  argument names guardrails can be written against (site, path, app)
 * @param platforms   where it is expected to exist, for the builder; routing uses manifests
 */
public record DeviceActionSpec(String action, int version, String capability,
                               ActionType actionType, String title, String description,
                               JsonNode input, JsonNode output, List<String> conditions,
                               List<String> platforms, int defaultTimeoutSec) {

  public static final String EXTENSION_PREFIX = "x-";

  public String ref() {
    return action + "@" + version;
  }

  public boolean extension() {
    return action.startsWith(EXTENSION_PREFIX);
  }

  /** Shell actions are never sent unless the owner turned them on for that device. */
  public boolean shell() {
    return action.startsWith("shell.");
  }

  /** An extra an app declared that the catalog does not know: judged as the riskiest kind. */
  public static DeviceActionSpec unknownExtension(String action, int version) {
    return new DeviceActionSpec(action, version, action.substring(0, Math.max(0,
        action.indexOf('.'))), ActionType.MONEY, action, "An action this device added itself",
        null, null, List.of(), List.of(), 120);
  }
}
