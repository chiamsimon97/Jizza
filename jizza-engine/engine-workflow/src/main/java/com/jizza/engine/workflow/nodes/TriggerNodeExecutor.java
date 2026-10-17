package com.jizza.engine.workflow.nodes;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.workflow.engine.NodeExecutor;
import org.springframework.stereotype.Component;

/** The entry node. It carries the run's input forward and does nothing else. */
@Component
public class TriggerNodeExecutor implements NodeExecutor {

  @Override
  public NodeType type() { return NodeType.TRIGGER; }

  @Override
  public Outcome execute(Execution execution) {
    return Outcome.completed(execution.context().input());
  }
}
