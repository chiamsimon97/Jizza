package com.jizza.engine.workflow.nodes;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor;
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
