package com.jizza.procedures.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.WorkflowStatus;
import com.jizza.common.error.JizzaException;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.procedure.BindingEnforcer;
import com.jizza.common.workflow.graph.WorkflowGraph;
import com.jizza.persistence.entity.ScheduledTaskEntity;
import com.jizza.persistence.entity.WorkflowEntity;
import com.jizza.persistence.entity.WorkflowVersionEntity;
import com.jizza.persistence.repository.ScheduledTaskRepository;
import com.jizza.persistence.repository.WorkflowRepository;
import com.jizza.persistence.repository.WorkflowVersionRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes "bound means bound" real (R36).
 *
 * <p>Before a bound job runs, three things are checked against persisted state: the task really is
 * bound to this workflow and version, that version is approved and enabled, and the graph about to
 * execute still hashes to what was reviewed. A mismatch raises an intervention and refuses the run
 * rather than executing something the user never saw.
 */
@Component
public class DefaultBindingEnforcer implements BindingEnforcer {

  private static final Logger log = LoggerFactory.getLogger(DefaultBindingEnforcer.class);

  private final ScheduledTaskRepository tasks;
  private final WorkflowRepository workflows;
  private final WorkflowVersionRepository versions;
  private final DeviationRecorder recorder;

  public DefaultBindingEnforcer(ScheduledTaskRepository tasks, WorkflowRepository workflows,
                                WorkflowVersionRepository versions, DeviationRecorder recorder) {
    this.tasks = tasks;
    this.workflows = workflows;
    this.versions = versions;
    this.recorder = recorder;
  }

  /**
   * Runs in a transaction of its own. Refusing throws, and a throw inside the caller's transaction
   * would mark it rollback-only -- which would take the caller's own record of the failed run down
   * with it.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void assertBound(UUID taskId, UUID workflowId, int version, JsonNode graph) {
    ScheduledTaskEntity task = tasks.findById(taskId)
        .orElseThrow(() -> new NotFoundException("Task", taskId));
    WorkflowEntity workflow = workflows.findById(workflowId)
        .orElseThrow(() -> new NotFoundException("Procedure", workflowId));

    if (!workflowId.equals(task.getBoundWorkflowId())
        || task.getBoundVersion() == null || task.getBoundVersion() != version) {
      throw deviation(task, workflow, "This job is not bound to that procedure version");
    }
    if (workflow.isDisabled()) {
      throw deviation(task, workflow, "The bound procedure is disabled");
    }
    if (workflow.getStatus() != WorkflowStatus.APPROVED) {
      throw deviation(task, workflow, "The bound procedure is " + workflow.getStatus()
          + " rather than approved");
    }

    WorkflowVersionEntity snapshot = versions.findByWorkflowIdAndVersion(workflowId, version)
        .orElseThrow(() -> deviation(task, workflow,
            "Version " + version + " of the bound procedure no longer exists"));

    // The fingerprint is what proves the graph is byte-for-byte the reviewed one. When the caller
    // does not hand one over, compare against the graph the engine would actually load: for the
    // current version that is the live row, not the snapshot -- comparing the snapshot with itself
    // would pass however far the live graph had drifted from what was reviewed.
    String approved = snapshot.getGraphFingerprint();
    JsonNode running = graph != null ? graph
        : workflow.getVersion() == version ? workflow.getGraph() : snapshot.getGraph();
    String actual = WorkflowGraph.from(running).fingerprint();
    if (approved != null && !approved.equals(actual)) {
      throw deviation(task, workflow,
          "The procedure about to run does not match the version that was approved");
    }
  }

  @Override
  public void reportDeviation(UUID runId, UUID taskId, String detail) {
    tasks.findById(taskId).ifPresent(task -> recorder.recordDeviation(task, detail, runId));
  }

  private JizzaException deviation(ScheduledTaskEntity task, WorkflowEntity workflow,
                                   String message) {
    log.warn("Refusing to run task {}: {}", task.getTitle(), message);
    recorder.record(task, workflow, message, null);
    return new JizzaException(HttpStatus.CONFLICT, "binding-violation",
        "Bound procedure mismatch", message);
  }
}
