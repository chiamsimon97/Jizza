package com.jizza.engine.devices.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.device.DeviceActionSpec;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The server's copy of the limits the person set on the device itself: the sites its browser may
 * open and the folders it may touch. The device enforces them anyway; checking here first turns a
 * command the device would refuse into a clear message before anything is sent.
 */
final class LocalLimits {

  private LocalLimits() {}

  /** Why the device would refuse this, or empty when its limits allow it (or it set none). */
  static Optional<String> check(DeviceActionSpec spec, JsonNode capabilities, JsonNode input) {
    if (capabilities == null || input == null) return Optional.empty();
    JsonNode cap = capabilities.path(spec.capability());
    if (spec.conditions().contains("site") && input.hasNonNull("url")) {
      List<String> sites = strings(cap.path("allowedSites"));
      if (!sites.isEmpty()) {
        String host = host(input.get("url").asText());
        if (host == null || sites.stream().noneMatch(p -> hostMatches(p, host))) {
          return Optional.of("the device only allows " + String.join(", ", sites)
              + " for its browser");
        }
      }
    }
    if (spec.conditions().contains("path")) {
      List<String> roots = strings(cap.path("roots"));
      if (!roots.isEmpty()) {
        for (String field : List.of("path", "from", "to", "cwd")) {
          if (!input.hasNonNull(field)) continue;
          String path = input.get(field).asText();
          if (roots.stream().noneMatch(r -> inside(path, r))) {
            return Optional.of("the device only allows the folders " + String.join(", ", roots));
          }
        }
      }
    }
    return Optional.empty();
  }

  static boolean hostMatches(String pattern, String host) {
    String p = pattern.trim().toLowerCase(Locale.ROOT);
    String h = host.toLowerCase(Locale.ROOT);
    if (p.equals("*")) return true;
    if (p.startsWith("*.")) return h.endsWith(p.substring(1)) || h.equals(p.substring(2));
    return h.equals(p);
  }

  /** Paths are compared as written: separators normalised, no climbing out with "..". */
  static boolean inside(String path, String root) {
    String p = path.replace('\\', '/');
    String r = root.replace('\\', '/');
    if (p.contains("/../") || p.endsWith("/..") || p.startsWith("../")) return false;
    if (!r.endsWith("/")) r = r + "/";
    return (p + "/").startsWith(r);
  }

  private static String host(String url) {
    try {
      return URI.create(url.trim()).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static List<String> strings(JsonNode node) {
    List<String> out = new ArrayList<>();
    if (node != null && node.isArray()) node.forEach(n -> out.add(n.asText()));
    return out;
  }
}
