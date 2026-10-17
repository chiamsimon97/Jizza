package com.jizza.engine.api.cosmos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Search across both stores; every filter is optional. */
public record MemorySearchQuery(String q, List<UUID> agentIds, List<String> categories,
                                String classification, String status, Instant from, Instant to) {

  public MemorySearchQuery {
    agentIds = agentIds == null ? List.of() : List.copyOf(agentIds);
    categories = categories == null ? List.of() : List.copyOf(categories);
  }
}
