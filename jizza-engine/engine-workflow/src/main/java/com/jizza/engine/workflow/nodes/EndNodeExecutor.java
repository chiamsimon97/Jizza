package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.ModuleContract;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.Templates;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * An explicit terminal node. A graph may also simply run out of outgoing edges.
 *
 * <p>Its output is what the run produced, which a workflow running this one as a step receives:
 * the declared {@code outputs} when there are any (a module's results, each a mapped value), else
 * {@code outputExpr} when set, otherwise the result of the step that led here (or of each, by id,
 * when several paths end here together). With outputs, {@code resultMessage} is a template over
 * {@code result.<output>} that becomes the run's one-line answer ("Booked. PNR 4512345678").
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
    List<ModuleContract.Field> outputs = ModuleContract.fields(config, "outputs");
    if (!outputs.isEmpty()) {
      ObjectNode result = Json.obj();
      try {
        for (ModuleContract.Field f : outputs) {
          result.set(f.name(), Templates.renderValue(f.value(), sandbox, execution.context().variables()));
        }
      } catch (SandboxViolationException e) {
        return Outcome.permanentFailure(e.getMessage());
      }
      // "Booked. PNR {{ result.pnr }}, {{ result.berth }}": the run's one-line answer, for chat.
      String template = config.path("resultMessage").asText("").strip();
      if (!template.isEmpty()) {
        java.util.Map<String, Object> variables = new java.util.HashMap<>(execution.context().variables());
        variables.put("result", Json.mapper().convertValue(result, Object.class));
        try {
          JsonNode message = Templates.renderValue(template, sandbox, variables);
          result.put("resultMessage", message.isTextual() ? message.asText() : message.toString());
        } catch (SandboxViolationException e) {
          // A message that cannot be written never fails a run that did its work.
          result.put("resultMessage", template);
        }
      }
      return Outcome.completed(result);
    }
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
