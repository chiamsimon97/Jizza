package com.jizza.engine.workflow.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.functions.ScriptFunctionDto;
import com.jizza.engine.api.workflow.functions.ScriptFunctions;
import com.jizza.engine.api.workflow.graph.ModuleContract;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Script step. It runs one of the person's saved functions (see {@link ScriptFunctions}): config
 * {@code functionId} and {@code inputs}, a map from each input's name to text with {@code {{ … }}}
 * placeholders over the run. A function that returns a map gives that map, so later steps map its
 * keys as {@code nodes.<step>.<key>}; any other result is {@code nodes.<step>.result}.
 *
 * <p>Steps saved before Functions existed hold the script itself, and still run: inputs mapped from
 * earlier steps, a few lines of Jizza script over them, and the outputs it declares.
 *
 * <p>Its config is {@code inputs} [{name, type, value}] where value is text with {@code {{ … }}}
 * placeholders over the run, {@code script}, and {@code outputs} [{name, type}]. The script reads
 * each input by its name and nothing else of the run, and gives a map from its {@code return} (or
 * last line); the step's result is the declared outputs taken from it, each read as its type.
 */
public final class ScriptStep {

  private ScriptStep() {}

  /**
   * Runs the step for {@code userId}, whose functions it runs and calls, with its inputs mapped
   * from {@code runData}. {@code functions} may be null where there are none.
   */
  public static JsonNode run(JexlSandbox sandbox, ScriptFunctions functions, UUID userId, JsonNode config,
                             Map<String, Object> runData) {
    FunctionLookup lookup = lookupFor(functions, userId);
    if (config == null || !config.hasNonNull("functionId")) return runInline(sandbox, config, runData, lookup);
    UUID id;
    try {
      id = UUID.fromString(config.path("functionId").asText());
    } catch (IllegalArgumentException e) {
      throw new SandboxViolationException("'functionId' is not a function id");
    }
    ScriptFunctionDto f = functions == null ? null : functions.find(id).orElse(null);
    if (f == null || (userId != null && !f.isPublic() && !userId.equals(f.userId()))) {
      throw new SandboxViolationException("The function this step runs was deleted");
    }
    Map<String, JsonNode> args = new LinkedHashMap<>();
    JsonNode mapped = config.path("inputs");
    for (String name : ScriptFunctionRunner.inputNames(f)) {
      String template = mapped.path(name).isTextual() ? mapped.path(name).asText() : null;
      if (template == null && mapped.has(name) && !mapped.get(name).isNull()) {
        args.put(name, mapped.get(name));
      } else if (template != null && !template.isBlank()) {
        args.put(name, Templates.renderValue(template, sandbox, runData));
      }
    }
    JsonNode result = sandbox.runFunction(f, args, lookup);
    if ("map".equals(f.returnType()) && result != null && result.isObject()) return result;
    ObjectNode out = Json.obj();
    out.set("result", result == null ? NullNode.getInstance() : result);
    return out;
  }

  /** How a script run for {@code userId} finds the functions it calls, each looked up once. */
  public static FunctionLookup lookupFor(ScriptFunctions functions, UUID userId) {
    if (functions == null || userId == null) return FunctionLookup.NONE;
    Map<String, Optional<ScriptFunctionDto>> seen = new HashMap<>();
    return name -> seen.computeIfAbsent(name, n -> functions.findByName(userId, n)).orElse(null);
  }

  /** A step saved with its own script: its inputs, script and outputs. */
  static JsonNode runInline(JexlSandbox sandbox, JsonNode config, Map<String, Object> runData, FunctionLookup lookup) {
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
    JsonNode result = sandbox.runScript(config == null ? null : config.path("script").asText(null), inputs, lookup);

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
