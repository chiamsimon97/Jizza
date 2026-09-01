package com.jizza.engine.api.orchestrator;

import com.jizza.engine.api.dto.AgentDto;
import java.util.UUID;

/**
 * Writes to the agent roster (R21).
 *
 * <p>Policy-free: it will create, rename or disable whatever it is asked to. Which agents an
 * account gets, what a valid name is and which of them must never be turned off are the platform's
 * decisions. Reads go through {@link AgentDirectory}.
 */
public interface AgentStore {

  /** Creates the agent if the account has none under this key, and returns it either way. */
  AgentDto createIfAbsent(UUID userId, AgentSpec spec);

  AgentDto rename(UUID agentId, String name);

  AgentDto setEnabled(UUID agentId, boolean enabled);
}
