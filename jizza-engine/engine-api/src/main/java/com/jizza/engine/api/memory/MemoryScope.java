package com.jizza.engine.api.memory;

import java.util.List;

/**
 * Which memories a reader may see. A memory is visible when it is shared (no domains) or shares a
 * domain with the reader; {@code allDomains} is the user looking at their own memory.
 */
public record MemoryScope(List<String> domains, boolean allDomains) {

  public static MemoryScope everything() { return new MemoryScope(List.of(), true); }

  public static MemoryScope sharedOnly() { return new MemoryScope(List.of(), false); }

  public static MemoryScope of(List<String> domains) { return new MemoryScope(List.copyOf(domains), false); }

  public boolean sees(List<String> memoryDomains) {
    if (allDomains || memoryDomains == null || memoryDomains.isEmpty()) return true;
    return memoryDomains.stream().anyMatch(domains::contains);
  }
}
