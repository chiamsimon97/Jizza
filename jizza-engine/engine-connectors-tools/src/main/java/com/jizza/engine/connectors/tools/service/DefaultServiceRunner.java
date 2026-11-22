package com.jizza.engine.connectors.tools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jizza.engine.api.connector.IdempotencyKey;
import com.jizza.engine.api.connector.ToolExecutor;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.service.ServiceModel;
import com.jizza.engine.api.service.ServiceModel.TriggerSample;
import com.jizza.engine.api.service.ServiceModel.WebhookDelivery;
import com.jizza.engine.api.service.ServiceRunner;
import com.jizza.engine.api.service.ServiceTriggerListeners;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.Redaction;
import com.jizza.engine.connectors.tools.registry.GeneratedToolProvider;
import com.jizza.engine.persistence.entity.GeneratedToolEntity;
import com.jizza.engine.persistence.entity.ServiceActionEntity;
import com.jizza.engine.persistence.entity.ServiceRunEntity;
import com.jizza.engine.persistence.entity.ServiceTriggerEntity;
import com.jizza.engine.persistence.entity.ConnectionEntity;
import com.jizza.engine.persistence.repository.ConnectionRepository;
import com.jizza.engine.persistence.repository.GeneratedToolRepository;
import com.jizza.engine.persistence.repository.ServiceActionRepository;
import com.jizza.engine.persistence.repository.ServiceRunRepository;
import com.jizza.engine.persistence.repository.ServiceTriggerRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

  private static final Logger log = LoggerFactory.getLogger(DefaultServiceRunner.class);
  /** A webhook body larger than this is refused rather than stored. */
  static final int MAX_WEBHOOK_BYTES = 512 * 1024;

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

  private ConnectionRepository services;
  private EncryptionService encryption;
  private ObjectProvider<ServiceTriggerListeners> listeners;

  /** What instant triggers and starting workflows need; absent in narrow unit tests. */
  @Autowired(required = false)
  public void setTriggerSupport(ConnectionRepository services, EncryptionService encryption,
                                ObjectProvider<ServiceTriggerListeners> listeners) {
    this.services = services;
    this.encryption = encryption;
    this.listeners = listeners;
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
    if (ServiceModel.WEBHOOK.equals(trigger.getKind())) {
      return trigger.getLastReceivedAt() == null
          ? new TriggerSample(false, "Nothing has arrived yet. Send a test event from the service to"
              + " this trigger's address.", 0, 0, Json.mapper().createArrayNode())
          : new TriggerSample(true, null, 0, 0, Json.mapper().createArrayNode());
    }
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
    if (ServiceModel.WEBHOOK.equals(trigger.getKind())) {
      return new TriggerSample(false, "This trigger waits for the service to call it; it isn't"
          + " polled.", 0, 0, Json.mapper().createArrayNode());
    }
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
    if (!fresh.isEmpty()) startWorkflows(trigger, fresh, "poll");
    // Quiet polls are not history; a baseline or a poll that found something is.
    if (baseline || !fresh.isEmpty()) {
      record(serviceId, "trigger", trigger.getId(), trigger.getLabel(),
          baseline ? "baseline" : "poll", ToolResult.ok(Json.obj()), null, Json.toNode(sample),
          started);
    }
    return sample;
  }

  @Override
  public WebhookDelivery receiveWebhook(String token, String rawBody, Map<String, String> headers) {
    long started = System.nanoTime();
    ServiceTriggerEntity trigger = token == null ? null
        : triggers.findByEndpointToken(token).orElse(null);
    if (trigger == null || !ServiceModel.WEBHOOK.equals(trigger.getKind())) {
      throw new NotFoundException("Trigger address", "");
    }
    UUID serviceId = trigger.getConnectionId();
    ConnectionEntity service = services == null ? null : services.findById(serviceId).orElse(null);
    if (service == null) throw new NotFoundException("Trigger address", "");
    if (!trigger.isEnabled()) return new WebhookDelivery(false, "This trigger is off", 0, 0);
    if (!ServiceModel.LIVE.equals(service.getVersionStatus())) {
      return new WebhookDelivery(false, "The service isn't published yet", 0, 0);
    }
    String body = rawBody == null ? "" : rawBody;
    if (body.getBytes(StandardCharsets.UTF_8).length > MAX_WEBHOOK_BYTES) {
      return refuse(trigger, "The event is too large", started);
    }
    if (!signatureMatches(trigger, body, headers)) {
      return refuse(trigger, "The signature didn't match", started);
    }

    JsonNode payload = parse(body);
    List<JsonNode> items;
    if (trigger.getItemsPath() != null && !trigger.getItemsPath().isBlank()) {
      try {
        items = items(payload, trigger.getItemsPath());
      } catch (ValidationException e) {
        return refuse(trigger, e.getMessage(), started);
      }
    } else {
      items = payload.isArray() ? items(payload, null) : List.of(payload);
    }
    // A service that sends an event twice starts nothing twice, when its items carry an id.
    Set<String> seen = new java.util.HashSet<>();
    if (trigger.getSeenIds() != null) trigger.getSeenIds().forEach(id -> seen.add(id.asText()));
    List<JsonNode> fresh = items.stream().filter(i -> {
      String id = idOf(i, trigger.getIdField());
      return id == null || !seen.contains(id);
    }).toList();
    trigger.setSeenIds(remember(trigger, items));
    trigger.setLastReceivedAt(Instant.now());
    triggers.save(trigger);

    int runs = fresh.isEmpty() ? 0 : startWorkflows(trigger, fresh, "webhook");
    TriggerSample sample = new TriggerSample(true, null, items.size(), fresh.size(), sample(fresh));
    record(serviceId, "trigger", trigger.getId(), trigger.getLabel(), "webhook",
        ToolResult.ok(Json.obj()), null, Json.toNode(sample), started);
    return new WebhookDelivery(true, "Received", fresh.size(), runs);
  }

  private WebhookDelivery refuse(ServiceTriggerEntity trigger, String reason, long started) {
    log.warn("Refused a delivery to webhook trigger {}: {}", trigger.getId(), reason);
    record(trigger.getConnectionId(), "trigger", trigger.getId(), trigger.getLabel(), "webhook",
        ToolResult.error(reason), null, null, started);
    return new WebhookDelivery(false, reason, 0, 0);
  }

  private boolean signatureMatches(ServiceTriggerEntity trigger, String body,
                                   Map<String, String> headers) {
    if (trigger.getSigningSecret() == null) return true;
    if (encryption == null) return false;
    String header = trigger.getSignatureHeader() == null || trigger.getSignatureHeader().isBlank()
        ? "x-signature" : trigger.getSignatureHeader().toLowerCase(java.util.Locale.ROOT);
    String provided = headers == null ? null : headers.get(header);
    if (provided == null) return false;
    String secret = encryption.decryptString(trigger.getSigningSecret());
    String expected = (trigger.getSignaturePrefix() == null ? "" : trigger.getSignaturePrefix())
        + hmacHex(secret, body);
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
        provided.trim().getBytes(StandardCharsets.UTF_8));
  }

  static String hmacHex(String secret, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
    }
  }

  /** A body that isn't JSON (a form post, plain text) is kept as text. */
  private static JsonNode parse(String body) {
    if (body.isBlank()) return Json.obj();
    try {
      return Json.read(body);
    } catch (RuntimeException e) {
      return Json.obj().put("text", body);
    }
  }

  private int startWorkflows(ServiceTriggerEntity trigger, List<JsonNode> items, String source) {
    ServiceTriggerListeners starter = listeners == null ? null : listeners.getIfAvailable();
    ConnectionEntity service = services == null ? null
        : services.findById(trigger.getConnectionId()).orElse(null);
    if (starter == null || service == null) return 0;
    var about = Json.obj().put("serviceId", service.getId().toString())
        .put("service", service.getServiceName()).put("trigger", trigger.getName())
        .put("label", trigger.getLabel()).put("source", source)
        .put("at", Instant.now().toString());
    try {
      return starter.start(trigger.getId(), service.getUserId(), items, about);
    } catch (RuntimeException e) {
      log.warn("Trigger {} could not start its workflows", trigger.getId(), e);
      return 0;
    }
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
