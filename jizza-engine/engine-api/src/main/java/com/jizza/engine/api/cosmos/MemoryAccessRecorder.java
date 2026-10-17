package com.jizza.engine.api.cosmos;

import java.util.Collection;
import java.util.UUID;

/**
 * Stamps a memory item's passport each time an agent is handed it: once per item, agent and run
 * (the {@link AccessContext} on the calling thread). Never blocks or fails the read it records.
 */
public interface MemoryAccessRecorder {

  void facts(UUID userId, UUID agentId, Collection<UUID> memoryIds);

  void notes(UUID userId, UUID agentId, Collection<UUID> entryIds);
}
