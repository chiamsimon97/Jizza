package com.jizza.engine.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * A set-variable step's output is the variables it set, so replaying its checkpoint after a
 * restart puts them back exactly as they were rather than working them out again.
 */
public final class SetVariables {

  private SetVariables() {}

  public static void apply(RunContext context, JsonNode output) {
    if (output == null || !output.isObject()) return;
    for (Map.Entry<String, JsonNode> entry : output.properties()) {
      context.setVar(entry.getKey(), entry.getValue());
    }
  }
}
