package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.enums.RunStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.WorkflowRunEntity;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Runs another workflow once per item of a list: the loop a DAG cannot have.
 *
 * <p>{@code items} is a JEXL expression over this run that must give a list; it is evaluated once
 * and the list kept in the step's state, so a restart works through the same items. Each item's
 * run gets {@code {"item": ..., "index": n}} as input, or {@code inputExpr} evaluated with
 * {@code item} and {@code index} added to the usual variables. {@code concurrency} items run at a
 * time (1 by default: one after another). The step completes with every item's result in order.
 *
 * <p>A failed item stops the step unless {@code continueOnError} is set, in which case it is listed
 * under {@code failed} and the rest carry on. Answering the intervention a stopped step raises
 * runs the failed items again and keeps the finished ones, so nothing that already happened is
 * done twice.
 */
@Component
public class ForEachNodeExecutor implements NodeExecutor {

  static final int DEFAULT_MAX_ITEMS = 100;
  static final int MAX_ITEMS = 500;
  static final int MAX_CONCURRENCY = 10;

  private final ChildRuns children;
  private final JexlSandbox sandbox;

  public ForEachNodeExecutor(ChildRuns children, JexlSandbox sandbox) {
    this.children = children;
    this.sandbox = sandbox;
  }

  @Override
  public NodeType type() { return NodeType.FOR_EACH; }

  @Override
  public Outcome execute(Execution execution) {
    JsonNode config = execution.node().config();
    ObjectNode state = execution.context().state(execution.node().id());
    UUID workflowId = ChildRuns.workflowIdOf(execution);
    if (workflowId == null) return Outcome.permanentFailure("Choose the workflow to run per item");

    if (!state.path("items").isArray()) {
      JsonNode items;
      try {
        items = sandbox.evaluate(config.path("items").asText(null),
            execution.context().variables());
      } catch (SandboxViolationException e) {
        return Outcome.permanentFailure(e.getMessage());
      }
      if (!items.isArray()) {
        return Outcome.permanentFailure("The items expression must give a list, not "
            + items.getNodeType().toString().toLowerCase(java.util.Locale.ROOT));
      }
      int limit = Math.min(config.path("maxItems").asInt(DEFAULT_MAX_ITEMS), MAX_ITEMS);
      if (items.size() > limit) {
        return Outcome.permanentFailure("There are " + items.size() + " items, more than this "
            + "step's limit of " + limit);
      }
      state.set("items", items);
      state.putArray("children");
    }

    ArrayNode items = (ArrayNode) state.get("items");
    ArrayNode started = state.path("children").isArray()
        ? (ArrayNode) state.get("children") : state.putArray("children");
    int concurrency = Math.max(1, Math.min(config.path("concurrency").asInt(1), MAX_CONCURRENCY));
    boolean continueOnError = config.path("continueOnError").asBoolean(false);

    Map<Integer, WorkflowRunEntity> byIndex = new HashMap<>();
    for (JsonNode entry : started) {
      Optional<WorkflowRunEntity> child =
          children.find(UUID.fromString(entry.path("runId").asText()));
      if (child.isEmpty()) {
        return Outcome.permanentFailure("The run for item " + entry.path("index").asInt()
            + " is gone");
      }
      byIndex.put(entry.path("index").asInt(), child.get());
    }

    int active = 0;
    for (WorkflowRunEntity child : byIndex.values()) {
      if (!ChildRuns.finished(child.getStatus())) active++;
    }
    Outcome stop = firstFailure(byIndex, continueOnError);
    if (stop != null) return stop;

    for (int index = 0; index < items.size() && active < concurrency; index++) {
      if (byIndex.containsKey(index)) continue;
      UUID childId;
      try {
        childId = children.start(execution, workflowId, inputFor(execution, items.get(index), index));
      } catch (SandboxViolationException | ValidationException | NotFoundException e) {
        return Outcome.permanentFailure("Item " + index + " could not start: " + e.getMessage());
      }
      started.addObject().put("index", index).put("runId", childId.toString());
      Optional<WorkflowRunEntity> child = children.find(childId);
      if (child.isEmpty()) return Outcome.permanentFailure("The run for item " + index + " is gone");
      byIndex.put(index, child.get());
      if (!ChildRuns.finished(child.get().getStatus())) active++;
      stop = firstFailure(byIndex, continueOnError);
      if (stop != null) return stop;
    }

    if (byIndex.size() < items.size() || active > 0) return Outcome.awaitChildren();

    ObjectNode output = Json.obj();
    ArrayNode results = output.putArray("results");
    ArrayNode failed = output.putArray("failed");
    for (int index = 0; index < items.size(); index++) {
      WorkflowRunEntity child = byIndex.get(index);
      if (child.getStatus() == RunStatus.DONE) {
        results.add(ChildRuns.resultOf(child));
      } else {
        results.addNull();
        failed.addObject().put("index", index).put("error", ChildRuns.whyNot(child));
      }
    }
    output.put("count", items.size());
    return Outcome.completed(output);
  }

  private Outcome firstFailure(Map<Integer, WorkflowRunEntity> byIndex, boolean continueOnError) {
    if (continueOnError) return null;
    return byIndex.entrySet().stream()
        .filter(e -> e.getValue().getStatus() == RunStatus.FAILED
            || e.getValue().getStatus() == RunStatus.CANCELLED)
        .min(Map.Entry.comparingByKey())
        .map(e -> Outcome.permanentFailure("Item " + e.getKey() + " did not finish: "
            + ChildRuns.whyNot(e.getValue())))
        .orElse(null);
  }

  private JsonNode inputFor(Execution execution, JsonNode item, int index) {
    JsonNode config = execution.node().config();
    if (config.hasNonNull("inputExpr")) {
      Map<String, Object> variables = execution.context().variables();
      variables.put("item", Json.mapper().convertValue(item, Object.class));
      variables.put("index", index);
      return sandbox.evaluate(config.get("inputExpr").asText(), variables);
    }
    ObjectNode input = Json.obj();
    input.set("item", item);
    input.put("index", index);
    return input;
  }
}
