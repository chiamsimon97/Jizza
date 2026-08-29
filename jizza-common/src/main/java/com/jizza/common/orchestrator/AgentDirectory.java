package com.jizza.common.orchestrator;

import com.jizza.common.dto.AgentDto;
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
}
