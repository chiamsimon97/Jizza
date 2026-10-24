package com.jizza.engine.workflow.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.FormulaPreview;
import com.jizza.engine.api.workflow.functions.ScriptFunctions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** {@link FormulaPreview} through the same sandbox, functions and templates as a workflow run. */
@Component
public class SandboxFormulaPreview implements FormulaPreview {

  /** The run data a formula can read; mirrors RunContext.variables(). */
  private static final Set<String> ROOTS = Set.of("input", "nodes", "vars", "instructions");

  private final JexlSandbox sandbox;
  private final ScriptFunctions functions;

  public SandboxFormulaPreview(JexlSandbox sandbox, ScriptFunctions functions) {
    this.sandbox = sandbox;
    this.functions = functions;
  }

  @Override
  public Result previewScript(java.util.UUID userId, JsonNode step, JsonNode data) {
    try {
      JsonNode value = ScriptStep.run(sandbox, functions, userId, step, variables(data));
      return new Result(value, typeOf(value), null);
    } catch (SandboxViolationException e) {
      return new Result(null, null, e.getMessage());
    }
  }

  @Override
  public Result preview(String formula, boolean template, JsonNode data) {
    Map<String, Object> variables = variables(data);
    try {
      JsonNode value = template
          ? Templates.renderValue(formula, sandbox, variables)
          : sandbox.evaluate(formula, variables);
      return new Result(value, typeOf(value), null);
    } catch (SandboxViolationException e) {
      return new Result(null, null, e.getMessage());
    }
  }

  private static Map<String, Object> variables(JsonNode data) {
    Map<String, Object> variables = new HashMap<>();
    for (String root : ROOTS) {
      JsonNode value = data == null ? null : data.get(root);
      variables.put(root, value == null || value.isNull()
          ? (root.equals("input") ? null : new HashMap<>())
          : Json.mapper().convertValue(value, Object.class));
    }
    return variables;
  }

  static String typeOf(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return "empty";
    if (value.isNumber()) return "number";
    if (value.isBoolean()) return "boolean";
    if (value.isArray()) return "list";
    if (value.isObject()) return "map";
    return "text";
  }
}
