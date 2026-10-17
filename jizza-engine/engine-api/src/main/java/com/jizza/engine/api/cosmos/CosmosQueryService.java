package com.jizza.engine.api.cosmos;

import java.util.List;
import java.util.UUID;

/**
 * The read side of the Memory Cosmos. User-only: it runs in the user's session, sees every domain,
 * and is never offered to an agent as a tool.
 */
public interface CosmosQueryService {

  CosmosSnapshot snapshot(UUID userId);

  ItemPage itemsOf(UUID userId, String bodyId, String cursor, int limit, boolean includeInactive);

  List<MemorySearchHit> search(UUID userId, MemorySearchQuery query);

  Passport passport(UUID userId, String itemKind, UUID id, String cursor);

  /** A restricted item's text. Callers put a fresh step-up in front of it. */
  Passport reveal(UUID userId, String itemKind, UUID id);

  /**
   * Every fact, note and subject placed in {@code bodyId}'s part of the sky (it and its moons),
   * inactive facts included. For the sun, that is everything.
   */
  BodyContents contentsOf(UUID userId, String bodyId);
}
