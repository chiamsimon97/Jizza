package com.jizza.engine.security.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.api.util.Json;
import java.time.Instant;
import java.util.UUID;

/**
 * The audit chain link (R14). Each entry hashes its own content together with the previous entry's
 * hash, so removing or editing any entry breaks every hash after it.
 */
public final class AuditHash {

  private AuditHash() {}

  public static final String GENESIS = "genesis";

  public static String compute(String prevHash, UUID userId, ActorType actorType, UUID actorId,
                               String action, String target, JsonNode detail, Instant createdAt) {
    String canonical = String.join("|",
        prevHash == null ? GENESIS : prevHash,
        String.valueOf(userId),
        String.valueOf(actorType),
        String.valueOf(actorId),
        String.valueOf(action),
        String.valueOf(target),
        detail == null || detail.isNull() ? "" : Json.write(jsonbOrder(detail)),
        createdAt == null ? "" : String.valueOf(createdAt.toEpochMilli()));
    return Hashing.sha256Hex(canonical);
  }

  /**
   * PostgreSQL's jsonb does not keep key order: it stores shorter keys first, then equal-length
   * keys bytewise. Hashing the detail in that order means an entry hashes the same when written as
   * when read back, however the caller happened to build it.
   */
  static JsonNode jsonbOrder(JsonNode node) {
    if (node.isObject()) {
      java.util.List<String> keys = new java.util.ArrayList<>();
      node.fieldNames().forEachRemaining(keys::add);
      keys.sort(JSONB_KEY_ORDER);
      com.fasterxml.jackson.databind.node.ObjectNode out =
          com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      for (String k : keys) out.set(k, jsonbOrder(node.get(k)));
      return out;
    }
    if (node.isArray()) {
      com.fasterxml.jackson.databind.node.ArrayNode out =
          com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
      node.forEach(e -> out.add(jsonbOrder(e)));
      return out;
    }
    return node;
  }

  private static final java.util.Comparator<String> JSONB_KEY_ORDER = (a, b) -> {
    byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (x.length != y.length) return Integer.compare(x.length, y.length);
    return java.util.Arrays.compareUnsigned(x, y);
  };
}
