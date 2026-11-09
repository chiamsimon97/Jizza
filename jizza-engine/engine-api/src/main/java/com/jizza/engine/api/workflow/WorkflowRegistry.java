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

  /**
   * Turns the workflow on or off for its triggers (a form submission, a schedule). On holds for
   * the current steps: a draft whose steps change some other way is off again until turned on.
   * Off stops a draft's triggers (it can still be tested by hand) and disables an approved one.
   */
  default WorkflowDto setEnabled(UUID workflowId, boolean enabled) {
    throw new UnsupportedOperationException("Turning workflows on and off is not supported here");
  }

  /** Makes submissions of {@code formId} start the workflow; null stops any form starting it. */
  default WorkflowDto setFormTrigger(UUID workflowId, UUID formId) {
    throw new UnsupportedOperationException("Form triggers are not supported here");
  }

  /** The workflow's runs that have not ended (running, waiting, or waiting for someone). */
  default List<UUID> unfinishedRuns(UUID workflowId) {
    return List.of();
  }

  /**
   * Deletes the workflow: it stops starting from its triggers and disappears from every list,
   * search and lookup, while its runs and their history stay. Cancel its unfinished runs first.
   */
  default void delete(UUID workflowId) {
    throw new UnsupportedOperationException("Deleting workflows is not supported here");
  }
}
