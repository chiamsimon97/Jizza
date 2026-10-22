package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor;
import org.springframework.stereotype.Component;

/**
 * Where parallel paths meet. The engine only runs a step once every path into it has resolved, so
 * by the time this runs the waiting is over; what it adds is one output holding each arriving
 * step's result by id, for the steps after it to read as {@code nodes.<joinId>.<stepId>}.
 */
@Component
public class JoinNodeExecutor implements NodeExecutor {

  @Override
  public NodeType type() { return NodeType.JOIN; }

  @Override
  public Outcome execute(Execution execution) {
    ObjectNode merged = Json.obj();
    for (String from : execution.context().liveInputs(execution.node().id())) {
      merged.set(from, execution.context().outputOf(from));
    }
    return Outcome.completed(merged);
  }
}
