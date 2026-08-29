package com.jizza.common.procedure;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * A bound job must run its approved graph exactly. Any deviation raises an intervention rather than
 * being silently improvised (R36).
 */
public interface BindingEnforcer {

  /** @throws com.jizza.common.error.JizzaException when the graph about to run is not the bound one. */
  void assertBound(UUID taskId, UUID workflowId, int version, JsonNode graph);

  /** Report a deviation detected at run time; raises an {@code instruction_required} notification. */
  void reportDeviation(UUID runId, UUID taskId, String detail);
}
