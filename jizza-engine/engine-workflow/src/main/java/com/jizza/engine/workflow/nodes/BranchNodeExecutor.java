package com.jizza.engine.workflow.nodes;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import org.springframework.stereotype.Component;

/**
 * Chooses which outgoing edge the run follows. The predicate is evaluated in the same sandbox as a
 * Transform, and the chosen outlet is checkpointed so a resumed run takes the same path it did
 * before the restart rather than re-deciding on data that may have moved on.
 */
@Component
public class BranchNodeExecutor implements NodeExecutor {

  private final JexlSandbox sandbox;

  public BranchNodeExecutor(JexlSandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Override
  public NodeType type() { return NodeType.BRANCH; }

  @Override
  public Outcome execute(Execution execution) {
    String expression = execution.node().config() == null
        ? null : execution.node().config().path("condition").asText(null);
    try {
      var value = sandbox.evaluate(expression, execution.context().variables());
      String outlet = value.asBoolean(false) ? "true" : "false";
      return Outcome.branched(outlet, Json.obj().put("condition", outlet));
    } catch (SandboxViolationException e) {
      return Outcome.permanentFailure(e.getMessage());
    }
  }
}
