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
        default -> { /* Trigger, intervention and end nodes need no extra configuration. */ }
      }

      if (node.type() != NodeType.END && node.type() != NodeType.BRANCH
          && graph.outgoing(node.id()).size() > 1) {
        throw new ValidationException("Node '" + node.id()
            + "' has more than one outgoing edge; only a branch node may fork");
      }
    }
  }

  private void requireConfig(WorkflowNode node, String key) {
    if (node.config() == null || !node.config().hasNonNull(key)) {
      throw new ValidationException(node.type() + " node '" + node.id() + "' needs a '" + key + "'");
    }
  }
}
