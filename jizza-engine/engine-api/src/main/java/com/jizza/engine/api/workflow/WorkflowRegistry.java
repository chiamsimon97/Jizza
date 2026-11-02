package com.jizza.engine.api.workflow;

import com.jizza.engine.api.dto.WorkflowDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read/write model over workflows and their version snapshots, shared with procedures and the
 * scheduler. Skills live behind {@link SkillStore}. */
public interface WorkflowRegistry {

  Optional<WorkflowDto> find(UUID workflowId);

  Optional<WorkflowDto> findVersion(UUID workflowId, int version);

  List<WorkflowDto> versions(UUID workflowId);

  List<WorkflowDto> list(UUID userId);

  WorkflowDto save(WorkflowDto workflow);

  /**
   * Keeps the current version as it is and makes {@code graph} the next one, status unchanged.
   * For a draft only: a reviewed workflow changes through review instead.
   */
  default WorkflowDto saveNewVersion(UUID workflowId, com.fasterxml.jackson.databind.JsonNode graph) {
    throw new UnsupportedOperationException("New versions are not supported here");
  }

  /** The agents that maintain the workflow, lead first; empty when none were chosen. */
  default List<UUID> maintainers(UUID workflowId) {
    return List.of();
  }

  /**
   * Chooses the agents that maintain the workflow, lead first. Changes no version: the steps and
   * the per-step guardrails of the current version stay as they are.
   */
  default void setMaintainers(UUID workflowId, List<UUID> agentIds) {
    throw new UnsupportedOperationException("Maintainers are not supported here");
  }
}
