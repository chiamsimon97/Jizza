package com.jizza.engine.api.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Access;
import java.util.List;
import java.util.UUID;

/**
 * Runs one literal browser action while a person configures a step, through the same device
 * broker and guardrails as a saved workflow.
 */
public interface DesignTimeService {

  record Error(String kind, String detail, JsonNode candidates) {}

  record Result(String status, JsonNode output, Error error, List<UUID> files, String message) {}

  Result testStep(Access access, UUID deviceId, String action, Integer version, JsonNode inputs);

  record PickStarted(UUID commandId) {}

  /** {@code status} is waiting, done or failed; the element is only ever the person's own pick. */
  record PickResult(String status, JsonNode element, String preview, String url, Error error) {}

  /** Asks the person's browser to let them click an element; returns at once. */
  PickStarted startPick(Access access, UUID deviceId, String sessionId, String url, String prompt);

  /** What the pick has produced so far; only the person who started it may read it. */
  PickResult pickResult(Access access, UUID commandId);
}
