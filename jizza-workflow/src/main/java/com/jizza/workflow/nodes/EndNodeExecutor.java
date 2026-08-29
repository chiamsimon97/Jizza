package com.jizza.workflow.nodes;

import com.jizza.common.enums.NodeType;
import com.jizza.common.util.Json;
import com.jizza.workflow.engine.NodeExecutor;
import org.springframework.stereotype.Component;

/** An explicit terminal node. A graph may also simply run out of outgoing edges. */
@Component
public class EndNodeExecutor implements NodeExecutor {

  @Override
  public NodeType type() { return NodeType.END; }

  @Override
  public Outcome execute(Execution execution) {
    return Outcome.completed(Json.obj().put("ended", true));
  }
}
