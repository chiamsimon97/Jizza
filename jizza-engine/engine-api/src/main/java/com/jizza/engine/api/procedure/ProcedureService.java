package com.jizza.engine.api.procedure;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.dto.WorkflowDto;
import java.util.List;
import java.util.UUID;

/** Learned procedures: propose → review → approve/bind → version/rollback (R34–R36). */
public interface ProcedureService {

  /** The user's learned procedures, newest first, with what the review screen needs to explain them. */
  List<ProcedureSummary> list(UUID userId);

  WorkflowDto propose(UUID agentId, UUID sourceTaskId, String name, String description, JsonNode graph);

  void approve(UUID workflowId);

  void reject(UUID workflowId, String feedback);

  /** Editing an approved procedure creates a new version and re-enters review (R36). */
  WorkflowDto revise(UUID workflowId, JsonNode graph);

  void rollback(UUID workflowId, int toVersion);

  void disable(UUID workflowId);

  List<WorkflowDto> history(UUID workflowId);

  ProcedureDiff diff(UUID workflowId, int fromVersion, int toVersion);

  /** Whether a procedure has already been proposed for this job, agent-authored or not. */
  boolean hasProposalFor(UUID sourceTaskId);

  /**
   * @param sourceTaskTitle the job it was learned from, or null when it came from no job
   * @param boundVersion the version that job follows now, or null when the job follows none of it
   * @param agentName the agent that wrote it down
   */
  record ProcedureSummary(WorkflowDto workflow, boolean disabled, String reviewFeedback,
                          String sourceTaskTitle, Integer boundVersion, String agentName) {}

  record ProcedureDiff(int fromVersion, int toVersion, List<String> addedNodes,
                       List<String> removedNodes, List<String> changedNodes) {}
}
