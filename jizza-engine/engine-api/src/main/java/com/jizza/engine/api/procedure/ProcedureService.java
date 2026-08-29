package com.jizza.engine.api.procedure;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.WorkflowDto;
import java.util.List;
import java.util.UUID;

/** Learned procedures: propose → review → approve/bind → version/rollback (R34–R36). */
public interface ProcedureService {

  WorkflowDto propose(UUID agentId, UUID sourceTaskId, String name, String description, JsonNode graph);

  void approve(UUID workflowId);

  void reject(UUID workflowId, String feedback);

  /** Editing an approved procedure creates a new version and re-enters review (R36). */
  WorkflowDto revise(UUID workflowId, JsonNode graph);

  void rollback(UUID workflowId, int toVersion);

  void disable(UUID workflowId);

  List<WorkflowDto> history(UUID workflowId);

  ProcedureDiff diff(UUID workflowId, int fromVersion, int toVersion);

  record ProcedureDiff(int fromVersion, int toVersion, List<String> addedNodes,
                       List<String> removedNodes, List<String> changedNodes) {}
}
