package com.jizza.engine.connectors.tools.execute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The few values in an API response worth showing a person: a balance, a date, a status, how many
 * holdings came back. It feeds the Memory panel's "Output data" and what agents may learn from a
 * job, so it is small and safe by construction: scalar values only, at most two levels deep, a
 * handful of them, each scrubbed and cut short, and nothing whose name suggests a credential.
 */
public final class OutputSummary {

  static final int MAX_FIELDS = 8;
  private static final int MAX_DEPTH = 2;
  private static final int MAX_VALUE = 120;
  private static final Pattern SECRET = Pattern.compile(
      "token|secret|password|passwd|api[_-]?key|auth|cookie|session|signature|credential|otp|pin",
      Pattern.CASE_INSENSITIVE);

  private OutputSummary() {}

  /** {"fields":[{"key":"balance","value":"12,400"}], "items": 12}; empty when nothing qualifies. */
  public static ObjectNode of(JsonNode body) {
    ObjectNode out = Json.obj();
    ArrayNode fields = out.putArray("fields");
    if (body == null || body.isNull() || body.isMissingNode()) return out;
    if (body.isArray()) {
      out.put("items", body.size());
      if (!body.isEmpty() && body.get(0).isObject()) collect(body.get(0), "", 1, fields);
    } else if (body.isObject()) {
      collect(body, "", 1, fields);
    } else if (body.isValueNode()) {
      fields.addObject().put("key", "value").put("value", clip(body.asText()));
    }
    return out;
  }

  private static void collect(JsonNode node, String prefix, int depth, ArrayNode fields) {
    Iterator<Map.Entry<String, JsonNode>> it = node.fields();
    while (it.hasNext() && fields.size() < MAX_FIELDS) {
      Map.Entry<String, JsonNode> e = it.next();
      String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
      if (SECRET.matcher(e.getKey()).find()) continue;
      JsonNode value = e.getValue();
      if (value.isValueNode() && !value.isNull()) {
        fields.addObject().put("key", key).put("value", clip(value.asText()));
      } else if (value.isArray()) {
        fields.addObject().put("key", key).put("value", value.size() + " items");
      } else if (value.isObject() && depth < MAX_DEPTH) {
        collect(value, key, depth + 1, fields);
      }
    }
  }

  private static String clip(String value) {
    String scrubbed = Redaction.scrub(value == null ? "" : value).strip();
    return scrubbed.length() <= MAX_VALUE ? scrubbed : scrubbed.substring(0, MAX_VALUE - 1) + "…";
  }

  /** One line for a model to read: "Zerodha get holdings: balance=12400; currency=INR; 12 items". */
  public static String line(String service, String label, JsonNode summary) {
    StringBuilder sb = new StringBuilder();
    sb.append(service == null ? "" : service).append(' ')
        .append(label == null ? "" : label.toLowerCase(Locale.ROOT)).append(':');
    for (JsonNode f : summary.path("fields")) {
      sb.append(' ').append(f.path("key").asText()).append('=').append(f.path("value").asText()).append(';');
    }
    if (summary.has("items")) sb.append(' ').append(summary.path("items").asInt()).append(" items");
    return sb.toString().strip();
  }
}
