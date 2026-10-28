package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.persistence.repository.WorkflowRunRepository;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.engine.RunContext;
import com.jizza.engine.workflow.engine.SagaWorkflowEngine;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Starting and reading the runs a step starts. The engine is looked up lazily: it is built from the
 * node executors, and these executors in turn start runs on it.
 */
@Component
public class ChildRuns {

  private final ObjectProvider<SagaWorkflowEngine> engine;
  private final WorkflowRunRepository runs;

  public ChildRuns(ObjectProvider<SagaWorkflowEngine> engine, WorkflowRunRepository runs) {
    this.engine = engine;
    this.runs = runs;
  }

  /**
   * Starts {@code workflowId} for this step and returns the new run's id. The child may already
   * have finished by the time this returns.
   */
  public UUID start(NodeExecutor.Execution execution, UUID workflowId, JsonNode input) {
    SagaWorkflowEngine saga = engine.getIfAvailable();
    if (saga == null) throw new ValidationException("The workflow engine is not available");
    boolean inheritBrowser = execution.node().config() == null
        || !execution.node().config().path("newBrowserSession").asBoolean(false);
    return saga.startChild(execution.runId(), execution.node().id(), workflowId, input,
        inheritBrowser).id();
  }

  public Optional<WorkflowRunEntity> find(UUID runId) {
    return runs.findById(runId);
  }

  public static boolean finished(RunStatus status) {
    return status == RunStatus.DONE || status == RunStatus.FAILED || status == RunStatus.CANCELLED;
  }

  /** What a finished child produced. */
  public static JsonNode resultOf(WorkflowRunEntity child) {
    return RunContext.of(child.getContext()).result();
  }

  /** Why a child did not finish, in words for the person reading the run. */
  public static String whyNot(WorkflowRunEntity child) {
    if (child.getStatus() == RunStatus.CANCELLED) return "it was cancelled";
    return child.getError() == null ? "it failed" : child.getError();
  }

  /** The workflow a step names in {@code workflowId}, or null when it names none or a bad id. */
  public static UUID workflowIdOf(NodeExecutor.Execution execution) {
    JsonNode config = execution.node().config();
    if (config == null || !config.path("workflowId").isTextual()) return null;
    try {
      return UUID.fromString(config.get("workflowId").asText());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
