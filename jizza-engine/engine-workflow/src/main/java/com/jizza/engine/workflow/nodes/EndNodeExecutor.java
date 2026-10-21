package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * An explicit terminal node. A graph may also simply run out of outgoing edges.
 *
 * <p>Its output is what the run produced, which a workflow running this one as a step receives:
 * {@code outputExpr} when set, otherwise the result of the step that led here (or of each, by id,
 * when several paths end here together).
 */
@Component
public class EndNodeExecutor implements NodeExecutor {

  private final JexlSandbox sandbox;

  public EndNodeExecutor(JexlSandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Override
  public NodeType type() { return NodeType.END; }

  @Override
  public Outcome execute(Execution execution) {
    JsonNode config = execution.node().config();
    String expression = config == null ? null : config.path("outputExpr").asText(null);
    if (expression != null && !expression.isBlank()) {
      try {
        return Outcome.completed(sandbox.evaluate(expression, execution.context().variables()));
      } catch (SandboxViolationException e) {
        return Outcome.permanentFailure(e.getMessage());
      }
    }
    List<String> inputs = execution.context().liveInputs(execution.node().id());
    if (inputs.size() == 1) return Outcome.completed(execution.context().outputOf(inputs.get(0)));
    ObjectNode merged = Json.obj();
    inputs.forEach(from -> merged.set(from, execution.context().outputOf(from)));
    return Outcome.completed(merged);
  }
}
