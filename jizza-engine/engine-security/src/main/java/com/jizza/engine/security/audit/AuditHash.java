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
        detail == null || detail.isNull() ? "" : Json.write(detail),
        createdAt == null ? "" : String.valueOf(createdAt.toEpochMilli()));
    return Hashing.sha256Hex(canonical);
  }
}
