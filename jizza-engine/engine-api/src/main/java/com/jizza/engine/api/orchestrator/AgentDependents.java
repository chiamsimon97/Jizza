package com.jizza.engine.api.orchestrator;

import java.util.List;
import java.util.UUID;

/**
 * What still points at an agent: workflows it owns or runs a step of, and tasks assigned to it.
 * Asked before an agent is archived or deleted, so nothing is left silently stranded.
 */
public interface AgentDependents {

  /** @param kind {@code workflow} or {@code task} */
  record Dependent(String kind, UUID id, String name, String status, boolean ownedByAgent) {}

  /** Every workflow (any version) and every task that names the agent. */
  List<Dependent> of(UUID userId, UUID agentId);

  /**
   * Hands the workflows the agent owns and its open tasks to another agent. Agent steps inside a
   * workflow are left as they are: changing them is an edit of the workflow, which is reviewed.
   *
   * @return how many workflows and tasks were reassigned
   */
  int reassign(UUID userId, UUID fromAgentId, UUID toAgentId);
}
