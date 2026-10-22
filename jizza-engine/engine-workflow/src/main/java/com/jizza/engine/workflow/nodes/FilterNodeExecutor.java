package com.jizza.engine.workflow.nodes;

import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.engine.SagaWorkflowEngine;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import org.springframework.stereotype.Component;

/**
 * Lets its path go on only when {@code condition} holds (Zapier's Filter). Otherwise the path
 * simply stops there: the steps after it are skipped, and the run still ends well, because nothing
 * went wrong.
 */
@Component
public class FilterNodeExecutor implements NodeExecutor {

  private final JexlSandbox sandbox;

  public FilterNodeExecutor(JexlSandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Override
  public NodeType type() { return NodeType.FILTER; }

  @Override
  public Outcome execute(Execution execution) {
    String expression = execution.node().config() == null
        ? null : execution.node().config().path("condition").asText(null);
    try {
      boolean passed = sandbox.evaluate(expression, execution.context().variables()).asBoolean(false);
      return Outcome.branched(passed ? SagaWorkflowEngine.FILTER_PASSED : "stop",
          Json.obj().put("passed", passed));
    } catch (SandboxViolationException e) {
      return Outcome.permanentFailure(e.getMessage());
    }
  }
}
