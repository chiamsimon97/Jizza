package com.jizza.engine.workflow.nodes;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor;
import org.springframework.stereotype.Component;

/**
 * Starts every path leaving it. The work is the engine's: once this step completes, each outgoing
 * edge is followed, where a branch would follow only one.
 */
@Component
public class ParallelNodeExecutor implements NodeExecutor {

  @Override
  public NodeType type() { return NodeType.PARALLEL; }

  @Override
  public Outcome execute(Execution execution) {
    return Outcome.completed(Json.obj());
  }
}
