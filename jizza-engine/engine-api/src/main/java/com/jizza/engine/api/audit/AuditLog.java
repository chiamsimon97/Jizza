package com.jizza.engine.api.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.AuditEntryDto;
import com.jizza.engine.api.enums.ActorType;
import java.util.List;
import java.util.UUID;

/** Append-only, hash-chained record of every consequential action (R14). */
public interface AuditLog {

  void record(UUID userId, ActorType actorType, UUID actorId, String action, String target, JsonNode detail);

  /** Verify the chain for a user; returns the id of the first broken entry, or empty when intact. */
  java.util.Optional<UUID> verifyChain(UUID userId);

  /** Most recent entries for a user, optionally filtered by action. {@code actorName} is unset --
   * resolving it to "You"/"Jizza"/an agent's name is the caller's call. */
  List<AuditEntryDto> recent(UUID userId, int limit, String action);

  long countForUser(UUID userId);
}
