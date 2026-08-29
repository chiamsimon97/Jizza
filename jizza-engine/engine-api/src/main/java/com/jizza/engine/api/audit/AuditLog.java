package com.jizza.engine.api.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActorType;
import java.util.UUID;

/** Append-only, hash-chained record of every consequential action (R14). */
public interface AuditLog {

  void record(UUID userId, ActorType actorType, UUID actorId, String action, String target, JsonNode detail);

  /** Verify the chain for a user; returns the id of the first broken entry, or empty when intact. */
  java.util.Optional<UUID> verifyChain(UUID userId);
}
