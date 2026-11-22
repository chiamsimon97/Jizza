package com.jizza.engine.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/** The workflows that start whenever a service trigger fires, one run for each new item. */
public interface ServiceTriggerListeners {

  /**
   * Starts every workflow of the trigger's owner that listens to it and is on.
   *
   * @param about  what fired, added to each run's input as {@code _trigger}
   * @return how many runs started
   */
  int start(UUID triggerId, UUID ownerId, List<JsonNode> items, JsonNode about);
}
