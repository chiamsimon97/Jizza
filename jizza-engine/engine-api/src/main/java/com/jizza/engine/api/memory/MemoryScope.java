package com.jizza.engine.api.memory;

import java.util.List;

/**
 * Which memories a reader may see. A memory is visible when it is shared (no domains) or shares a
 * domain with the reader; {@code allDomains} sees every domain. Restricted facts are seen only with
 * {@code withRestricted}, which only the user looking at their own memory has: no agent is ever
 * handed one, whatever its domains.
 */
public record MemoryScope(List<String> domains, boolean allDomains, boolean withRestricted) {

  /** The user, looking at their own memory. */
  public static MemoryScope everything() { return new MemoryScope(List.of(), true, true); }

  /** An agent allowed every domain (the orchestrator, when configured so); still no restricted facts. */
  public static MemoryScope everyDomain() { return new MemoryScope(List.of(), true, false); }

  public static MemoryScope sharedOnly() { return new MemoryScope(List.of(), false, false); }

  public static MemoryScope of(List<String> domains) {
    return new MemoryScope(List.copyOf(domains), false, false);
  }

  /** Whether a memory with these domains is in scope, restriction aside. */
  public boolean sees(List<String> memoryDomains) {
    if (allDomains || memoryDomains == null || memoryDomains.isEmpty()) return true;
    return memoryDomains.stream().anyMatch(domains::contains);
  }

  public boolean sees(List<String> memoryDomains, boolean restricted) {
    return (withRestricted || !restricted) && sees(memoryDomains);
  }
}
