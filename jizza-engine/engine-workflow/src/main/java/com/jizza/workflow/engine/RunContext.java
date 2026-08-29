package com.jizza.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.common.util.Json;
import java.util.HashMap;
import java.util.Map;

/**
 * The serialized saga state stored in {@code workflow_runs.context}.
 *
 * <p>Everything a run needs to continue lives here, which is what makes resumption after a restart
 * possible: a fresh process reads the row and knows exactly as much as the one that died.
 *
 * <pre>
 * {
 *   "input":        { ... },              // what started the run
 *   "nodes":        { "nodeId": output }, // each completed node's result
 *   "instructions": { "nodeId": "text" }, // answers to intervention nodes
 *   "branch":       { "nodeId": "true" }, // which outlet a branch took
 *   "pausedFor":    "instruction",        // or "permission"
 *   "pausedNode":   "nodeId"
 * }
 * </pre>
 */
public final class RunContext {

  private final ObjectNode root;

  private RunContext(ObjectNode root) {
    this.root = root;
  }

  public static RunContext of(JsonNode existing) {
    ObjectNode node = existing != null && existing.isObject()
        ? ((ObjectNode) existing).deepCopy() : Json.obj();
    if (!node.has("nodes")) node.putObject("nodes");
    if (!node.has("instructions")) node.putObject("instructions");
    if (!node.has("branch")) node.putObject("branch");
    return new RunContext(node);
  }

  public static RunContext start(JsonNode input) {
    RunContext context = of(null);
    context.root.set("input", input == null ? Json.obj() : input);
    return context;
  }

  public JsonNode toJson() { return root; }

  public JsonNode input() { return root.path("input"); }

  public JsonNode outputOf(String nodeId) { return root.path("nodes").path(nodeId); }

  public void recordOutput(String nodeId, JsonNode output) {
    ((ObjectNode) root.get("nodes")).set(nodeId, output == null ? Json.obj() : output);
  }

  public void recordBranch(String nodeId, String outlet) {
    ((ObjectNode) root.get("branch")).put(nodeId, outlet);
  }

  public String branchOf(String nodeId) {
    JsonNode value = root.path("branch").path(nodeId);
    return value.isTextual() ? value.asText() : null;
  }

  public void recordInstruction(String nodeId, String instruction) {
    ((ObjectNode) root.get("instructions")).put(nodeId, instruction);
  }

  public String instructionFor(String nodeId) {
    JsonNode value = root.path("instructions").path(nodeId);
    return value.isTextual() ? value.asText() : null;
  }

  public void pause(String reason, String nodeId) {
    root.put("pausedFor", reason);
    root.put("pausedNode", nodeId);
  }

  public void clearPause() {
    root.remove("pausedFor");
    root.remove("pausedNode");
  }

  public String pausedFor() {
    return root.path("pausedFor").isTextual() ? root.get("pausedFor").asText() : null;
  }

  public String pausedNode() {
    return root.path("pausedNode").isTextual() ? root.get("pausedNode").asText() : null;
  }

  /** The variable bindings a Transform or Branch expression is evaluated against. */
  public Map<String, Object> variables() {
    Map<String, Object> variables = new HashMap<>();
    variables.put("input", Json.mapper().convertValue(input(), Object.class));
    variables.put("nodes", Json.mapper().convertValue(root.path("nodes"), Object.class));
    variables.put("instructions", Json.mapper().convertValue(root.path("instructions"), Object.class));
    return variables;
  }
}
