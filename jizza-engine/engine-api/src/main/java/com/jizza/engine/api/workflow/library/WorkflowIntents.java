package com.jizza.engine.api.workflow.library;

import java.util.UUID;

/**
 * How a person would ask for a saved workflow ("book a train ticket", "IRCTC booking"), so a chat
 * request in their own words finds a recording named "Simon". Suggested by the cheap model from the
 * workflow's name, steps and the sites it opens, and kept on its reuse profile, where the person
 * can change them.
 */
public interface WorkflowIntents {

  /**
   * Suggests what the workflow is for and the requests it handles, saves them on its profile and
   * returns it. The guardrails the person set are kept.
   *
   * @throws com.jizza.engine.api.error.NotFoundException   when it is not their workflow
   * @throws com.jizza.engine.api.error.ValidationException when no suggestion could be made
   */
  WorkflowLibrary.Match suggest(UUID userId, UUID workflowId);

  /**
   * As {@link #suggest}, after the current transaction commits and off the caller's thread, and
   * only when the workflow has no example requests yet. Failures are logged, never thrown.
   */
  void suggestLater(UUID userId, UUID workflowId);
}
