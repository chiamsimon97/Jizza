package com.jizza.engine.workflow.engine;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.workflow.graph.WorkflowEdge;
import com.jizza.engine.api.workflow.graph.WorkflowGraph;
import com.jizza.engine.api.workflow.graph.WorkflowNode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Structural checks beyond {@link WorkflowGraph#validate()}: the things that would only surface as
 * a confusing failure halfway through a run.
 */
@Component
public class WorkflowValidator {

  public void validate(WorkflowGraph graph) {
    graph.validate();

    for (WorkflowNode node : graph.nodes()) {
      switch (node.type()) {
        case TRANSFORM -> requireConfig(node, "expression");
        case BRANCH -> {
          requireConfig(node, "condition");
          List<WorkflowEdge> outgoing = graph.outgoing(node.id());
          boolean hasTrue = outgoing.stream().anyMatch(e -> "true".equals(e.branch()));
          boolean hasFalse = outgoing.stream().anyMatch(e -> "false".equals(e.branch()));
          if (!hasTrue || !hasFalse) {
            throw new ValidationException("Branch node '" + node.id()
                + "' needs an outgoing edge for both 'true' and 'false'");
          }
        }
        case CONNECTOR_TOOL -> requireConfig(node, "toolId");
        case AGENT_STEP -> {
          requireConfig(node, "agentId");
          if (node.config() == null
              || (!node.config().hasNonNull("instruction")
                  && !node.config().hasNonNull("instructionExpr"))) {
            throw new ValidationException("Agent step '" + node.id()
                + "' needs an 'instruction' or 'instructionExpr'");
          }
        }
        case PARALLEL -> {
          if (graph.outgoing(node.id()).size() < 2) {
            throw new ValidationException("Parallel step '" + node.id()
                + "' needs at least two paths leaving it");
          }
        }
        case WAIT -> validateWait(node);
        case SUB_WORKFLOW -> requireWorkflowId(node);
        case FOR_EACH -> {
          requireWorkflowId(node);
          requireConfig(node, "items");
        }
        default -> { /* Trigger, intervention, join and end nodes need no extra configuration. */ }
      }

      if (node.type() != NodeType.END && node.type() != NodeType.BRANCH
          && node.type() != NodeType.PARALLEL && graph.outgoing(node.id()).size() > 1) {
        throw new ValidationException("Node '" + node.id()
            + "' has more than one outgoing edge; only a branch or parallel step may fork");
      }
    }
  }

  private void validateWait(WorkflowNode node) {
    var config = node.config();
    boolean duration = config != null && config.hasNonNull("duration");
    if (!duration && (config == null
        || (!config.hasNonNull("until") && !config.hasNonNull("untilExpr")))) {
      throw new ValidationException("Wait step '" + node.id()
          + "' needs a 'duration', an 'until' time or an 'untilExpr'");
    }
    if (duration) {
      try {
        java.time.Duration parsed = java.time.Duration.parse(config.get("duration").asText());
        if (parsed.isNegative()) throw new java.time.format.DateTimeParseException("", "", 0);
      } catch (java.time.format.DateTimeParseException e) {
        throw new ValidationException("Wait step '" + node.id() + "' has a duration that is not "
            + "an ISO-8601 duration such as PT30M or P1D");
      }
    }
  }

  private void requireWorkflowId(WorkflowNode node) {
    requireConfig(node, "workflowId");
    try {
      java.util.UUID.fromString(node.config().get("workflowId").asText());
    } catch (IllegalArgumentException e) {
      throw new ValidationException(node.type() + " node '" + node.id()
          + "' names a workflow id that is not valid");
    }
  }

  private void requireConfig(WorkflowNode node, String key) {
    if (node.config() == null || !node.config().hasNonNull(key)) {
      throw new ValidationException(node.type() + " node '" + node.id() + "' needs a '" + key + "'");
    }
  }
}
