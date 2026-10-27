package com.jizza.devicesim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The exact text a signature covers: compact JSON with every object's keys in code-point order,
 * and no whitespace. Each platform's app rebuilds the same text from the body it received before
 * checking the signature, so the order fields arrived in never matters.
 */
final class Canonical {

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private Canonical() {}

  static String of(JsonNode node) {
    StringBuilder out = new StringBuilder();
    write(node, out);
    return out.toString();
  }

  private static void write(JsonNode node, StringBuilder out) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      out.append("null");
    } else if (node.isObject()) {
      List<String> names = new ArrayList<>();
      for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
        names.add(it.next().getKey());
      }
      names.sort(String::compareTo);
      out.append('{');
      for (int i = 0; i < names.size(); i++) {
        if (i > 0) out.append(',');
        out.append(quote(names.get(i))).append(':');
        write(node.get(names.get(i)), out);
      }
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) out.append(',');
        write(node.get(i), out);
      }
      out.append(']');
    } else {
      try {
        out.append(MAPPER.writeValueAsString(node));
      } catch (JsonProcessingException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static String quote(String s) {
    try {
      return MAPPER.writeValueAsString(s);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
