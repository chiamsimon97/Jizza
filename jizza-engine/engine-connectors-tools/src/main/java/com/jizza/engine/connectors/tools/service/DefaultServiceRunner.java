package com.jizza.engine.connectors.tools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jizza.engine.api.connector.IdempotencyKey;
import com.jizza.engine.api.connector.ToolExecutor;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.service.ServiceModel.TriggerSample;
import com.jizza.engine.api.service.ServiceRunner;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import com.jizza.engine.connectors.tools.registry.GeneratedToolProvider;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.entity.ServiceActionEntity;
import com.jizza.engine.persistence.entity.ServiceRunEntity;
import com.jizza.engine.persistence.entity.ServiceTriggerEntity;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.ServiceActionRepository;
import com.jizza.engine.persistence.repository.ServiceRunRepository;
import com.jizza.engine.persistence.repository.ServiceTriggerRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Runs actions and polls triggers through {@link ToolExecutor}, then records each run.
 *
 * <p>Nothing here holds a transaction open across the HTTP call: a slow service must not pin a
 * database connection, so reads and the history write are separate short operations.
 */
@Service
public class DefaultServiceRunner implements ServiceRunner {

  /** How many seen ids a trigger remembers; older ones fall off as new ones arrive. */
  static final int MAX_SEEN = 1000;
  /** History keeps a sample of what came back, not an unbounded copy of the service's data. */
  private static final int MAX_RECORDED_CHARS = 20_000;
  private static final int MAX_SAMPLE_ITEMS = 20;

  private final ServiceActionRepository actions;
  private final ServiceTriggerRepository triggers;
  private final GeneratedToolRepository apis;
  private final ServiceRunRepository runs;
  private final ToolExecutor executor;

  public DefaultServiceRunner(ServiceActionRepository actions, ServiceTriggerRepository triggers,
                              GeneratedToolRepository apis, ServiceRunRepository runs,
                              ToolExecutor executor) {
    this.actions = actions;
    this.triggers = triggers;
    this.apis = apis;
    this.runs = runs;
    this.executor = executor;
  }

  @Override
  public ToolResult runAction(UUID serviceId, UUID actionId, JsonNode args, String source,
                              IdempotencyKey key) {
    ServiceActionEntity action = JpaServiceDefinitions.scoped(actions.findById(actionId),
        serviceId, ServiceActionEntity::getConnectionId, "Action", actionId);
    GeneratedToolEntity api = api(serviceId, action.getApiId());
    JsonNode input = args == null ? Json.obj() : args;

    long started = System.nanoTime();
    ToolResult result;
    try {
      result = executor.execute(GeneratedToolProvider.toDto(api), input, key);
    } catch (RuntimeException e) {
      result = ToolResult.error(Redaction.scrub(String.valueOf(e.getMessage())));
    }
    record(serviceId, "test".equals(source) ? "test" : "action", action.getId(), action.getLabel(),
        source, result, input, result.output(), started);
    return result;
  }

  @Override
  public TriggerSample testTrigger(UUID serviceId, UUID triggerId) {
    ServiceTriggerEntity trigger = trigger(serviceId, triggerId);
    long started = System.nanoTime();
    Fetched fetched = fetch(serviceId, trigger);
    TriggerSample sample = fetched.error != null
        ? new TriggerSample(false, fetched.error, 0, 0, Json.mapper().createArrayNode())
        : new TriggerSample(true, null, fetched.items.size(),
            unseen(trigger, fetched.items).size(), sample(fetched.items));
    record(serviceId, "test", trigger.getId(), trigger.getLabel(), "test",
        sample.ok() ? ToolResult.ok(Json.obj()) : ToolResult.error(sample.error()), null,
        Json.toNode(sample), started);
    return sample;
  }

  @Override
  public TriggerSample pollTrigger(UUID serviceId, UUID triggerId) {
    ServiceTriggerEntity trigger = trigger(serviceId, triggerId);
    long started = System.nanoTime();
    Fetched fetched = fetch(serviceId, trigger);
    if (fetched.error != null) {
      trigger.setLastPolledAt(Instant.now());
      triggers.save(trigger);
      TriggerSample failed = new TriggerSample(false, fetched.error, 0, 0,
          Json.mapper().createArrayNode());
      record(serviceId, "trigger", trigger.getId(), trigger.getLabel(), "poll",
          ToolResult.error(fetched.error), null, null, started);
      return failed;
    }

    // The first poll only takes a baseline. Otherwise switching a trigger on would report every
    // item the service has ever held as new.
    boolean baseline = trigger.getLastPolledAt() == null;
    List<JsonNode> fresh = baseline ? List.of() : unseen(trigger, fetched.items);
    trigger.setSeenIds(remember(trigger, fetched.items));
    trigger.setLastPolledAt(Instant.now());
    triggers.save(trigger);

    TriggerSample sample = new TriggerSample(true, null, fetched.items.size(), fresh.size(),
        sample(fresh));
    // Quiet polls are not history; a baseline or a poll that found something is.
    if (baseline || !fresh.isEmpty()) {
      record(serviceId, "trigger", trigger.getId(), trigger.getLabel(),
          baseline ? "baseline" : "poll", ToolResult.ok(Json.obj()), null, Json.toNode(sample),
          started);
    }
    return sample;
  }

  // ── Internals ─────────────────────────────────────────────────────────────

  private record Fetched(List<JsonNode> items, String error) {}

  private Fetched fetch(UUID serviceId, ServiceTriggerEntity trigger) {
    GeneratedToolEntity api = api(serviceId, trigger.getApiId());
    ToolResult result;
    try {
      result = executor.execute(GeneratedToolProvider.toDto(api), Json.obj(), null);
    } catch (RuntimeException e) {
      return new Fetched(List.of(), Redaction.scrub(String.valueOf(e.getMessage())));
    }
    if (!result.ok()) return new Fetched(List.of(), result.error());
    try {
      return new Fetched(items(result.output().path("body"), trigger.getItemsPath()), null);
    } catch (ValidationException e) {
      return new Fetched(List.of(), e.getMessage());
    }
  }

  /**
   * Finds the list of items in a response: at {@code itemsPath} (dot separated) when given, the
   * body itself when it is a list, or the only list-valued field of an object body.
   */
  static List<JsonNode> items(JsonNode body, String itemsPath) {
    JsonNode node = body;
    if (itemsPath != null && !itemsPath.isBlank()) {
      for (String part : itemsPath.split("\\.")) node = node.path(part);
    } else if (node.isObject()) {
      List<JsonNode> arrays = new ArrayList<>();
      node.elements().forEachRemaining(v -> { if (v.isArray()) arrays.add(v); });
      if (arrays.size() == 1) node = arrays.get(0);
    }
    if (!node.isArray()) {
      throw new ValidationException("The response has no list of items"
          + (itemsPath == null || itemsPath.isBlank() ? "; set the items path" : " at '" + itemsPath + "'"));
    }
    List<JsonNode> list = new ArrayList<>();
    node.forEach(list::add);
    return list;
  }

  private static List<JsonNode> unseen(ServiceTriggerEntity trigger, List<JsonNode> items) {
    Set<String> seen = new java.util.HashSet<>();
    if (trigger.getSeenIds() != null) trigger.getSeenIds().forEach(id -> seen.add(id.asText()));
    return items.stream().filter(item -> {
      String id = idOf(item, trigger.getIdField());
      return id != null && !seen.contains(id);
    }).toList();
  }

  private static ArrayNode remember(ServiceTriggerEntity trigger, List<JsonNode> items) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    if (trigger.getSeenIds() != null) trigger.getSeenIds().forEach(id -> ids.add(id.asText()));
    for (JsonNode item : items) {
      String id = idOf(item, trigger.getIdField());
      if (id != null) {
        ids.remove(id);
        ids.add(id);
      }
    }
    List<String> kept = new ArrayList<>(ids);
    if (kept.size() > MAX_SEEN) kept = kept.subList(kept.size() - MAX_SEEN, kept.size());
    ArrayNode out = Json.mapper().createArrayNode();
    kept.forEach(out::add);
    return out;
  }

  static String idOf(JsonNode item, String idField) {
    JsonNode id = item;
    for (String part : (idField == null || idField.isBlank() ? "id" : idField).split("\\.")) {
      id = id.path(part);
    }
    return id.isMissingNode() || id.isNull() ? null : id.asText();
  }

  private static ArrayNode sample(List<JsonNode> items) {
    ArrayNode out = Json.mapper().createArrayNode();
    items.stream().limit(MAX_SAMPLE_ITEMS).forEach(out::add);
    return out;
  }

  private GeneratedToolEntity api(UUID serviceId, UUID apiId) {
    GeneratedToolEntity api = JpaServiceDefinitions.scoped(apis.findById(apiId), serviceId,
        GeneratedToolEntity::getConnectionId, "API", apiId);
    if (!api.isEnabled()) throw new NotFoundException("API", apiId);
    return api;
  }

  private ServiceTriggerEntity trigger(UUID serviceId, UUID triggerId) {
    return JpaServiceDefinitions.scoped(triggers.findById(triggerId), serviceId,
        ServiceTriggerEntity::getConnectionId, "Trigger", triggerId);
  }

  private void record(UUID serviceId, String kind, UUID refId, String refName, String source,
                      ToolResult result, JsonNode input, JsonNode output, long startedNanos) {
    ServiceRunEntity run = new ServiceRunEntity();
    run.setConnectionId(serviceId);
    run.setKind(kind);
    run.setRefId(refId);
    run.setRefName(refName);
    run.setSource(source);
    run.setStatus(result.ok() ? "ok" : "error");
    run.setDurationMs((System.nanoTime() - startedNanos) / 1_000_000);
    run.setInput(bounded(input));
    run.setOutput(bounded(output));
    run.setError(result.ok() ? null : result.error());
    runs.save(run);
  }

  /** Keeps a recorded payload small, and scrubbed of anything that looks like a credential. */
  private static JsonNode bounded(JsonNode node) {
    if (node == null || node.isNull()) return null;
    String text = Redaction.scrub(Json.write(node));
    if (text.length() <= MAX_RECORDED_CHARS) {
      try {
        return Json.read(text);
      } catch (RuntimeException e) {
        // Scrubbing can, rarely, break the JSON; the text is still worth keeping.
      }
    }
    return Json.obj().put("truncated", text.length() > MAX_RECORDED_CHARS)
        .put("preview", text.length() <= MAX_RECORDED_CHARS ? text
            : text.substring(0, MAX_RECORDED_CHARS));
  }
}
