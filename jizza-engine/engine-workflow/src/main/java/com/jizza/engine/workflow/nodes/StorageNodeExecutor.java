package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NodeType;
import com.jizza.engine.api.error.SandboxViolationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.WorkflowStorageEntity;
import com.jizza.engine.persistence.repository.WorkflowStorageRepository;
import com.jizza.engine.workflow.engine.NodeExecutor;
import com.jizza.engine.workflow.sandbox.JexlSandbox;
import com.jizza.engine.workflow.sandbox.Templates;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Keeps values between runs (Zapier's Storage): the last order number seen, a running count, a
 * list to collect into. {@code action} is one of {@code get} (with an optional {@code default}),
 * {@code set} and {@code append} (with {@code valueExpr}; append keeps the newest {@code maxItems}),
 * {@code increment} (by {@code by}, 1 unless set) and {@code delete}. {@code key} is a template, so
 * a value can be kept per customer or per day.
 *
 * <p>Values belong to the user and are shared by all of their workflows. The output is the
 * {@code key}, the {@code value} after the step and the {@code previous} one.
 */
@Component
public class StorageNodeExecutor implements NodeExecutor {

  static final int MAX_KEY_CHARS = 200;
  static final int MAX_VALUE_CHARS = 64 * 1024;
  static final int MAX_KEYS_PER_USER = 10_000;
  static final int MAX_LIST_ITEMS = 1_000;

  private final JexlSandbox sandbox;
  private final WorkflowStorageRepository storage;
  private final Clock clock;

  @Autowired
  public StorageNodeExecutor(JexlSandbox sandbox, WorkflowStorageRepository storage) {
    this(sandbox, storage, Clock.systemUTC());
  }

  public StorageNodeExecutor(JexlSandbox sandbox, WorkflowStorageRepository storage, Clock clock) {
    this.sandbox = sandbox;
    this.storage = storage;
    this.clock = clock;
  }

  @Override
  public NodeType type() { return NodeType.STORAGE; }

  @Override
  public Outcome execute(Execution execution) {
    JsonNode config = execution.node().config();
    if (config == null) return Outcome.permanentFailure("The storage step has no settings");
    Map<String, Object> variables = execution.context().variables();
    String action = config.path("action").asText("get");

    String key;
    JsonNode value = null;
    try {
      key = Templates.render(config.path("key").asText(""), sandbox, variables).strip();
      if (action.equals("set") || action.equals("append")) {
        value = sandbox.evaluate(config.path("valueExpr").asText(null), variables);
      }
    } catch (SandboxViolationException e) {
      return Outcome.permanentFailure("The storage step could not work out its value: "
          + e.getMessage());
    }
    if (key.isEmpty() || key.length() > MAX_KEY_CHARS) {
      return Outcome.permanentFailure("The storage key must be 1 to " + MAX_KEY_CHARS
          + " characters");
    }

    var userId = execution.userId();
    if (action.equals("get")) {
      JsonNode stored = storage.findByUserIdAndKey(userId, key)
          .map(WorkflowStorageEntity::getValue).orElse(null);
      JsonNode result = stored != null ? stored
          : config.has("default") ? config.get("default") : NullNode.getInstance();
      return Outcome.completed(output(key, result, stored));
    }

    var existing = storage.lockByUserIdAndKey(userId, key);
    JsonNode previous = existing.map(WorkflowStorageEntity::getValue).orElse(null);
    if (action.equals("delete")) {
      existing.ifPresent(storage::delete);
      return Outcome.completed(output(key, NullNode.getInstance(), previous));
    }

    JsonNode next;
    switch (action) {
      case "set" -> next = value;
      case "increment" -> {
        double by = config.path("by").asDouble(1);
        double current = previous != null && previous.isNumber() ? previous.asDouble() : 0;
        double sum = current + by;
        next = sum == Math.rint(sum) && Math.abs(sum) < 1e15
            ? Json.mapper().getNodeFactory().numberNode((long) sum)
            : Json.mapper().getNodeFactory().numberNode(sum);
      }
      case "append" -> {
        int maxItems = Math.min(MAX_LIST_ITEMS, Math.max(1, config.path("maxItems").asInt(100)));
        ArrayNode list = previous != null && previous.isArray()
            ? ((ArrayNode) previous).deepCopy() : Json.mapper().createArrayNode();
        list.add(value);
        while (list.size() > maxItems) list.remove(0);
        next = list;
      }
      default -> {
        return Outcome.permanentFailure("The storage step has an unknown action '" + action + "'");
      }
    }
    if (next != null && next.toString().length() > MAX_VALUE_CHARS) {
      return Outcome.permanentFailure("The value for '" + key + "' is larger than "
          + MAX_VALUE_CHARS + " characters");
    }
    if (existing.isEmpty() && storage.countByUserId(userId) >= MAX_KEYS_PER_USER) {
      return Outcome.permanentFailure("Your workflows already keep " + MAX_KEYS_PER_USER
          + " values; delete some before adding more");
    }

    WorkflowStorageEntity row = existing.orElseGet(() -> {
      WorkflowStorageEntity fresh = new WorkflowStorageEntity();
      fresh.setUserId(userId);
      fresh.setKey(key);
      return fresh;
    });
    row.setValue(next);
    row.setUpdatedAt(Instant.now(clock));
    storage.save(row);
    return Outcome.completed(output(key, next, previous));
  }

  private static ObjectNode output(String key, JsonNode value, JsonNode previous) {
    ObjectNode out = Json.obj().put("key", key);
    out.set("value", value == null ? NullNode.getInstance() : value);
    out.set("previous", previous == null ? NullNode.getInstance() : previous);
    return out;
  }
}
