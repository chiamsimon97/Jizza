package com.jizza.workflow.nodes;

import com.jizza.common.enums.NodeType;
import com.jizza.common.error.SandboxViolationException;
import com.jizza.workflow.engine.NodeExecutor;
import com.jizza.workflow.sandbox.JexlSandbox;
import org.springframework.stereotype.Component;

/**
 * Reshapes data between nodes, in the JEXL sandbox and nowhere else (R9).
 *
 * <p>A sandbox violation is a permanent failure, never a retry: an expression that tried to touch
 * the filesystem will try again on the next attempt, and the user should hear about it.
 */
@Component
public class TransformNodeExecutor implements NodeExecutor {

  private final JexlSandbox sandbox;

  public TransformNodeExecutor(JexlSandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Override
  public NodeType type() { return NodeType.TRANSFORM; }

  @Override
  public Outcome execute(Execution execution) {
    String expression = execution.node().config() == null
        ? null : execution.node().config().path("expression").asText(null);
    try {
      return Outcome.completed(sandbox.evaluate(expression, execution.context().variables()));
    } catch (SandboxViolationException e) {
      return Outcome.permanentFailure(e.getMessage());
    }
  }
}
