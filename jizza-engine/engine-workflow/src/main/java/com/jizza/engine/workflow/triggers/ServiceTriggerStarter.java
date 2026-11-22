package com.jizza.engine.workflow.triggers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.service.ServiceTriggerListeners;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowEngine;
import com.jizza.engine.persistence.entity.WorkflowEntity;
import com.jizza.engine.persistence.repository.WorkflowRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Starts the workflows that listen to a service trigger, one run per new item, the way a form's
 * submission starts its workflows. An item's fields are the run's input, so later steps map them
 * by name; {@code _trigger} says what fired.
 */
@Component
public class ServiceTriggerStarter implements ServiceTriggerListeners {

  private static final Logger log = LoggerFactory.getLogger(ServiceTriggerStarter.class);

  /** A burst of events starts at most this many runs per workflow; the rest stay in history. */
  static final int MAX_RUNS_PER_FIRE = 20;

  private final WorkflowRepository workflows;
  private final ObjectProvider<WorkflowEngine> engine;

  public ServiceTriggerStarter(WorkflowRepository workflows, ObjectProvider<WorkflowEngine> engine) {
    this.workflows = workflows;
    this.engine = engine;
  }

  @Override
  public int start(UUID triggerId, UUID ownerId, List<JsonNode> items, JsonNode about) {
    WorkflowEngine runner = engine.getIfAvailable();
    if (runner == null || items.isEmpty()) return 0;
    int started = 0;
    for (WorkflowEntity w : workflows.findByServiceTriggerIdAndDeletedAtIsNull(triggerId)) {
      if (!w.getUserId().equals(ownerId) || !w.isEnabled()) continue;
      for (JsonNode item : items.stream().limit(MAX_RUNS_PER_FIRE).toList()) {
        try {
          runner.start(w.getId(), w.getVersion(), input(item, about), "trigger:" + triggerId);
          started++;
        } catch (RuntimeException e) {
          log.warn("Service trigger {} could not start workflow {}", triggerId, w.getId(), e);
          break;
        }
      }
    }
    return started;
  }

  static ObjectNode input(JsonNode item, JsonNode about) {
    ObjectNode input = item != null && item.isObject() ? ((ObjectNode) item).deepCopy() : Json.obj();
    if (item != null && !item.isObject()) input.set("item", item);
    input.set("_trigger", about == null ? Json.obj() : about);
    return input;
  }
}
