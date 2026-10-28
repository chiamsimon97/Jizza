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
}
