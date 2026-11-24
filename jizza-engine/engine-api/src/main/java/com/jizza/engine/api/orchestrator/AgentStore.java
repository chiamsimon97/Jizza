package com.jizza.engine.api.orchestrator;

import com.jizza.engine.api.dto.AgentDto;
import java.util.List;
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

  /** Whether the agent may search the web and read pages. */
  default boolean webAccess(UUID agentId) {
    return false;
  }

  default void setWebAccess(UUID agentId, boolean on) {
    throw new UnsupportedOperationException("Web access is not supported");
  }

  /** The model the agent answers with by default (a model reference); null for its tier. */
  default String model(UUID agentId) {
    return null;
  }

  default void setModel(UUID agentId, String model) {
    throw new UnsupportedOperationException("Agent models are not supported");
  }

  /**
   * Creates a custom agent under {@code key}, with its own memory domain equal to the key. Draft
   * and archived agents are stored disabled whatever else is asked.
   */
  default AgentDto createCustom(UUID userId, String key, CustomAgentDraft draft) {
    throw new UnsupportedOperationException("Custom agents are not supported");
  }

  /** Replaces what a custom agent is; its key and domain never change. */
  default AgentDto updateCustom(UUID agentId, CustomAgentDraft draft) {
    throw new UnsupportedOperationException("Custom agents are not supported");
  }

  /** Changes the lifecycle status; draft and archived also disable the agent, active enables it. */
  default AgentDto setStatus(UUID agentId, String status) {
    throw new UnsupportedOperationException("Custom agents are not supported");
  }

  default AgentDto setParent(UUID agentId, UUID parentAgentId) {
    throw new UnsupportedOperationException("Custom agents are not supported");
  }

  /** Deletes the agent and everything that cascades from it. */
  default void delete(UUID agentId) {
    throw new UnsupportedOperationException("Custom agents are not supported");
  }

  default AgentRevision recordRevision(UUID agentId, UUID changedByUserId) {
    throw new UnsupportedOperationException("Custom agents are not supported");
  }

  default List<AgentRevision> revisions(UUID agentId) {
    return List.of();
  }
}
