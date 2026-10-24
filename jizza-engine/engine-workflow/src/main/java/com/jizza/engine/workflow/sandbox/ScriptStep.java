package com.jizza.engine.workflow.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.graph.ModuleContract;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Script step: inputs mapped from earlier steps, a few lines of Jizza script over them, and the
 * outputs it declares, which later steps map as {@code nodes.<step>.<name>}.
 *
 * <p>Its config is {@code inputs} [{name, type, value}] where value is text with {@code {{ … }}}
 * placeholders over the run, {@code script}, and {@code outputs} [{name, type}]. The script reads
 * each input by its name and nothing else of the run, and gives a map from its {@code return} (or
 * last line); the step's result is the declared outputs taken from it, each read as its type.
 */
public final class ScriptStep {

  private ScriptStep() {}

  /** Runs the step's script over its inputs, mapped from {@code runData}. */
  public static JsonNode run(JexlSandbox sandbox, JsonNode config, Map<String, Object> runData) {
    Map<String, Object> inputs = new HashMap<>();
    for (ModuleContract.Field f : ModuleContract.fields(config, "inputs")) {
      if (f.name().isBlank()) continue;
      JsonNode value = f.value() == null || f.value().isBlank()
          ? NullNode.getInstance() : Templates.renderValue(f.value(), sandbox, runData);
      if (!value.isNull() && !(value.isTextual() && value.asText().isBlank())) {
        JsonNode typed = ModuleContract.coerce(value, f.type());
        if (typed == null) {
          throw new SandboxViolationException("The input '" + f.name() + "' should be "
              + ModuleContract.typeName(f.type()) + ", not " + value);
        }
        value = typed;
      }
      inputs.put(f.name(), Json.mapper().convertValue(value, Object.class));
    }
    JsonNode result = sandbox.runScript(config == null ? null : config.path("script").asText(null), inputs);

    List<ModuleContract.Field> outputs = ModuleContract.fields(config, "outputs");
    if (outputs.isEmpty()) return result;
    if (result == null || !result.isObject()) {
      throw new SandboxViolationException("The script should end with return {'"
          + outputs.get(0).name() + "': …} giving its outputs, but it gave "
          + (result == null || result.isNull() ? "nothing" : result.toString()));
    }
    ObjectNode out = Json.obj();
    for (ModuleContract.Field f : outputs) {
      JsonNode value = result.get(f.name());
      if (value == null || value.isNull()) {
        out.putNull(f.name());
        continue;
      }
      JsonNode typed = ModuleContract.coerce(value, f.type());
      if (typed == null) {
        throw new SandboxViolationException("The output '" + f.name() + "' should be "
            + ModuleContract.typeName(f.type()) + ", not " + value);
      }
      out.set(f.name(), typed);
    }
    return out;
  }
}
