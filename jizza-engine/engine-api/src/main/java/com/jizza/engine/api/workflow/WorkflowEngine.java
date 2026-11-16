package com.jizza.engine.api.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.RunDto;
import java.util.UUID;

/** The Postgres-backed saga engine (R33). Every node result is checkpointed before the next runs. */
public interface WorkflowEngine {

  RunDto start(UUID workflowId, int version, JsonNode input, String triggerSource);

  /**
   * As {@link #start}, with some steps' settings pointed at the run's input for this run only:
   * {@code {"nodeId": [{"path": [...], "value": "{{ input.field }}"}]}}. A form made from a
   * workflow's steps starts it this way.
   */
  default RunDto start(UUID workflowId, int version, JsonNode input, String triggerSource,
                       JsonNode stepOverrides) {
    return start(workflowId, version, input, triggerSource);
  }

  /**
   * As {@link #start}, with the browser its browser steps should use when the person chose one
   * (null for whichever of their browsers is free, as usual).
   */
  default RunDto start(UUID workflowId, int version, JsonNode input, String triggerSource,
                       JsonNode stepOverrides, UUID preferredBrowser) {
    return stepOverrides == null ? start(workflowId, version, input, triggerSource)
        : start(workflowId, version, input, triggerSource, stepOverrides);
  }

  /** Continue a run paused on a human-intervention node, feeding the instruction downstream. */
  RunDto resume(UUID runId, JsonNode instruction);

  /** Re-drive a run that was interrupted mid-flight, from its last completed node. */
  RunDto resumeAfterRestart(UUID runId);

  void cancel(UUID runId);

  RunDto get(UUID runId);
}
