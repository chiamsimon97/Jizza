package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.engine.SetVariables;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import org.springframework.stereotype.Component;

/**
 * Sets run variables (Zoho Flow's Set Variable): {@code variables} is a list of
 * {@code {name, expr}}, worked out in order so a later one can use an earlier one. Every later step
 * reads them as {@code vars.<name>}, and setting a name again replaces it, which makes counters and
 * running totals possible without an agent.
 */
@Component
public class SetVariableNodeExecutor implements NodeExecutor {

  private final JexlSandbox sandbox;

  public SetVariableNodeExecutor(JexlSandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Override
  public NodeType type() { return NodeType.SET_VARIABLE; }

  @Override
  public Outcome execute(Execution execution) {
    JsonNode variables = execution.node().config() == null
        ? null : execution.node().config().get("variables");
    if (variables == null || !variables.isArray()) {
      return Outcome.permanentFailure("The step has no variables to set");
    }
    ObjectNode set = Json.obj();
    for (JsonNode v : variables) {
      String name = v.path("name").asText();
      try {
        JsonNode value = sandbox.evaluate(v.path("expr").asText(null),
            execution.context().variables());
        set.set(name, value);
        SetVariables.apply(execution.context(), Json.obj().set(name, value));
      } catch (SandboxViolationException e) {
        return Outcome.permanentFailure("Variable '" + name + "': " + e.getMessage());
      }
    }
    return Outcome.completed(set);
  }
}
