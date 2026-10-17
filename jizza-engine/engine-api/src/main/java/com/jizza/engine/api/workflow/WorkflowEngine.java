package com.jizza.engine.api.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.RunDto;
import java.util.UUID;

/** The Postgres-backed saga engine (R33). Every node result is checkpointed before the next runs. */
public interface WorkflowEngine {

  RunDto start(UUID workflowId, int version, JsonNode input, String triggerSource);

  /** Continue a run paused on a human-intervention node, feeding the instruction downstream. */
  RunDto resume(UUID runId, JsonNode instruction);

  /** Re-drive a run that was interrupted mid-flight, from its last completed node. */
  RunDto resumeAfterRestart(UUID runId);

  void cancel(UUID runId);

  RunDto get(UUID runId);
}
