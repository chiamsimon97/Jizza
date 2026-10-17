package com.jizza.engine.api.orchestrator;

import com.jizza.engine.api.dto.AgentDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read model over {@code agents}, shared by modules that need to name or resolve an agent. */
public interface AgentDirectory {

  List<AgentDto> list(UUID userId);

  Optional<AgentDto> byId(UUID agentId);

  Optional<AgentDto> byKey(UUID userId, String key);

  /** The orchestrator agent for the user; every user has exactly one. */
  AgentDto orchestrator(UUID userId);

  String nameOf(UUID agentId);

  /** The agent's direct reports. */
  default List<AgentDto> reportsOf(UUID agentId) {
    return List.of();
  }

  /** The agent's managers, nearest first, ending with Jizza. Empty for Jizza itself. */
  default List<AgentDto> managersOf(UUID agentId) {
    return List.of();
  }

  /** Every agent, in any account, made from this organization template. */
  default List<AgentDto> fromTemplate(UUID templateId) {
    return List.of();
  }
}
