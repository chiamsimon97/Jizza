package com.jizza.engine.workflow.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDto;
import com.jizza.engine.api.workflow.graph.ModuleContract;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reading a function's inputs as it declares them, and its result as its return type. */
final class ScriptFunctionRunner {

  private ScriptFunctionRunner() {}

  static List<ModuleContract.Field> inputs(ScriptFunctionDto f) {
    return ModuleContract.fields(Json.obj().set("inputs", f.inputs()), "inputs").stream()
        .filter(i -> !i.name().isBlank()).toList();
  }

  static List<String> inputNames(ScriptFunctionDto f) {
    return inputs(f).stream().map(ModuleContract.Field::name).toList();
  }

  /** The script's variables: each declared input, defaulted, checked when required, and typed. */
  static Map<String, Object> arguments(ScriptFunctionDto f, Map<String, JsonNode> given) {
    Map<String, Object> out = new HashMap<>();
    for (ModuleContract.Field input : inputs(f)) {
      JsonNode value = given == null ? null : given.get(input.name());
      if (blank(value) && input.defaultValue() != null) value = input.defaultValue();
      if (blank(value)) {
        if (input.required()) {
          throw new SandboxViolationException("fn." + f.name() + " needs the input " + input.name());
        }
        out.put(input.name(), null);
        continue;
      }
      JsonNode typed = ModuleContract.coerce(value, input.type());
      if (typed == null) {
        throw new SandboxViolationException("The input " + input.name() + " of fn." + f.name() + " should be "
            + ModuleContract.typeName(input.type()) + ", not " + value);
      }
      out.put(input.name(), Json.mapper().convertValue(typed, Object.class));
    }
    return out;
  }

  /** What the script gave, as the function's return type. */
  static JsonNode result(ScriptFunctionDto f, JsonNode value) {
    String type = f.returnType() == null ? "any" : f.returnType();
    if (value == null || value.isNull() || "any".equals(type)) return value;
    JsonNode typed = ModuleContract.coerce(value, type);
    if (typed == null) {
      throw new SandboxViolationException("fn." + f.name() + " should return " + ModuleContract.typeName(type)
          + ", but it gave " + value);
    }
    return typed;
  }

  private static boolean blank(JsonNode value) {
    return value == null || value.isNull() || value.isMissingNode() || (value.isTextual() && value.asText().isBlank());
  }
}
