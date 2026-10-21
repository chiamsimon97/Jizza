package com.jizza.engine.workflow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   "pausedNode":   "nodeId",
 *   "approvals":    { "nodeId": true }, // steps the user approved after a pause
 *
 *   "ready":    ["nodeId"],                       // steps that can run now, in order
 *   "parked":   { "nodeId": { "reason": "timer", "wakeAt": "..." } },
 *                                                 // steps waiting on a time, child runs or a person
 *   "arrivals": { "nodeId": { "fromId": true } }, // which incoming paths reached a step
 *                                                 // (false: a branch went the other way)
 *   "state":    { "nodeId": { ... } },            // a step's own bookkeeping between attempts
 *   "result":   { ... },                          // what the run produced, for a parent run
 *   "parent":   { "runId": "...", "nodeId": "..." },
 *   "depth":    0,                                // how deep in sub-workflows this run is
 *   "legacy":   true                              // started before parallel paths existed
 * }
 * </pre>
 *
 * <p>The frontier ({@code ready} and {@code parked}) is what lets several paths be in flight at
 * once: a path parked on a wait or a question does not hold up the others.
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

  /**
   * Records that the user approved the action this step paused for, so running the step again
   * goes ahead instead of asking a second time. It covers this step of this run only.
   */
  public void recordApproval(String nodeId) {
    ObjectNode approvals = root.has("approvals") && root.get("approvals").isObject()
        ? (ObjectNode) root.get("approvals") : root.putObject("approvals");
    approvals.put(nodeId, true);
  }

  public boolean approved(String nodeId) {
    return root.path("approvals").path(nodeId).asBoolean(false);
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

  // ── the frontier: which steps can run, and which are waiting ─────────────

  /** Whether the run already tracks its frontier; runs from before parallel paths do not. */
  public boolean hasFrontier() { return root.has("ready"); }

  /**
   * Starts tracking a run that predates the frontier, from the one step it was on. Such a run
   * keeps the old rule that a step runs when the first path reaches it, because paths its
   * branches skipped were never recorded.
   */
  public void startFrontier(String nodeId, boolean legacy) {
    ArrayNode ready = root.putArray("ready");
    if (nodeId != null) ready.add(nodeId);
    root.putObject("parked");
    root.putObject("arrivals");
    if (legacy) root.put("legacy", true);
  }

  public boolean legacy() { return root.path("legacy").asBoolean(false); }

  public List<String> ready() {
    List<String> ids = new ArrayList<>();
    root.path("ready").forEach(n -> ids.add(n.asText()));
    return ids;
  }

  public void enqueue(String nodeId) {
    ArrayNode ready = readyArray();
    for (JsonNode n : ready) if (n.asText().equals(nodeId)) return;
    ready.add(nodeId);
  }

  public void dequeue(String nodeId) {
    ArrayNode ready = readyArray();
    for (int i = ready.size() - 1; i >= 0; i--) {
      if (ready.get(i).asText().equals(nodeId)) ready.remove(i);
    }
  }

  private ArrayNode readyArray() {
    return root.has("ready") && root.get("ready").isArray()
        ? (ArrayNode) root.get("ready") : root.putArray("ready");
  }

  /** A step that cannot go on yet. {@code wakeAt} is set for a timer, null otherwise. */
  public void park(String nodeId, String reason, Instant wakeAt) {
    dequeue(nodeId);
    ObjectNode entry = parkedObject().putObject(nodeId);
    entry.put("reason", reason);
    if (wakeAt != null) entry.put("wakeAt", wakeAt.toString());
  }

  /** Moves a parked step back to the ready list. */
  public void unpark(String nodeId) {
    parkedObject().remove(nodeId);
    enqueue(nodeId);
  }

  public boolean isParked(String nodeId) { return parkedObject().has(nodeId); }

  /** Parked step id to why it is parked. */
  public Map<String, String> parked() {
    Map<String, String> out = new LinkedHashMap<>();
    parkedObject().fields().forEachRemaining(e ->
        out.put(e.getKey(), e.getValue().path("reason").asText()));
    return out;
  }

  public Instant wakeAt(String nodeId) {
    JsonNode value = parkedObject().path(nodeId).path("wakeAt");
    return value.isTextual() ? Instant.parse(value.asText()) : null;
  }

  private ObjectNode parkedObject() {
    return root.has("parked") && root.get("parked").isObject()
        ? (ObjectNode) root.get("parked") : root.putObject("parked");
  }

  /**
   * Records that the path from {@code from} reached {@code to}, or was cut off on the way. Two
   * edges between the same pair (a branch sending both outlets to one step) count as one path,
   * live if either was.
   */
  public void arrive(String to, String from, boolean live) {
    ObjectNode arrivals = root.has("arrivals") && root.get("arrivals").isObject()
        ? (ObjectNode) root.get("arrivals") : root.putObject("arrivals");
    ObjectNode forNode = arrivals.has(to) && arrivals.get(to).isObject()
        ? (ObjectNode) arrivals.get(to) : arrivals.putObject(to);
    forNode.put(from, live || forNode.path(from).asBoolean(false));
  }

  /** Incoming step id to whether its path arrived live, for the paths that have resolved. */
  public Map<String, Boolean> arrivals(String nodeId) {
    Map<String, Boolean> out = new LinkedHashMap<>();
    root.path("arrivals").path(nodeId).fields().forEachRemaining(e ->
        out.put(e.getKey(), e.getValue().asBoolean()));
    return out;
  }

  /** The steps whose paths actually reached {@code nodeId}, in arrival order. */
  public List<String> liveInputs(String nodeId) {
    return arrivals(nodeId).entrySet().stream().filter(Map.Entry::getValue)
        .map(Map.Entry::getKey).toList();
  }

  // ── a step's own bookkeeping, and what the run produced ──────────────────

  /** Scratch space a step keeps between its attempts, such as the child runs it started. */
  public ObjectNode state(String nodeId) {
    ObjectNode all = root.has("state") && root.get("state").isObject()
        ? (ObjectNode) root.get("state") : root.putObject("state");
    return all.has(nodeId) && all.get(nodeId).isObject()
        ? (ObjectNode) all.get(nodeId) : all.putObject(nodeId);
  }

  public boolean hasState(String nodeId) { return root.path("state").has(nodeId); }

  /** Every run a step of this run started, as recorded in the steps' {@code children} lists. */
  public List<java.util.UUID> childRunIds() {
    List<java.util.UUID> ids = new ArrayList<>();
    root.path("state").forEach(state -> state.path("children").forEach(child -> {
      if (child.path("runId").isTextual()) {
        ids.add(java.util.UUID.fromString(child.get("runId").asText()));
      }
    }));
    return ids;
  }

  public void recordLast(String nodeId) { root.put("last", nodeId); }

  public String last() { return root.path("last").isTextual() ? root.get("last").asText() : null; }

  public void recordResult(JsonNode result) {
    root.set("result", result == null ? Json.obj() : result);
  }

  /** What the run produced, which a parent run's step receives as its output. */
  public JsonNode result() { return root.path("result"); }

  public void recordParent(java.util.UUID parentRunId, String parentNodeId, int depth) {
    root.putObject("parent").put("runId", parentRunId.toString()).put("nodeId", parentNodeId);
    root.put("depth", depth);
  }

  public java.util.UUID parentRunId() {
    JsonNode value = root.path("parent").path("runId");
    return value.isTextual() ? java.util.UUID.fromString(value.asText()) : null;
  }

  public int depth() { return root.path("depth").asInt(0); }

  /** The variable bindings a Transform or Branch expression is evaluated against. */
  public Map<String, Object> variables() {
    Map<String, Object> variables = new HashMap<>();
    variables.put("input", Json.mapper().convertValue(input(), Object.class));
    variables.put("nodes", Json.mapper().convertValue(root.path("nodes"), Object.class));
    variables.put("instructions", Json.mapper().convertValue(root.path("instructions"), Object.class));
    return variables;
  }
}
