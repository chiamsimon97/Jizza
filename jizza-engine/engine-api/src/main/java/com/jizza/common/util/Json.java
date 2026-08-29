package com.jizza.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;

/** One shared ObjectMapper for module-internal serialization that must not depend on web config. */
public final class Json {

  private Json() {}

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public static ObjectMapper mapper() { return MAPPER; }

  public static ObjectNode obj() { return MAPPER.createObjectNode(); }

  public static JsonNode of(Map<String, ?> map) { return MAPPER.valueToTree(map); }

  public static JsonNode toNode(Object o) { return MAPPER.valueToTree(o); }

  public static String write(Object o) {
    try {
      return MAPPER.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to serialize " + o.getClass().getSimpleName(), e);
    }
  }

  public static JsonNode read(String s) {
    try {
      return s == null ? null : MAPPER.readTree(s);
    } catch (Exception e) {
      throw new com.jizza.common.error.ValidationException("Malformed JSON: " + e.getMessage());
    }
  }

  public static <T> T convert(JsonNode node, Class<T> type) {
    return MAPPER.convertValue(node, type);
  }
}
