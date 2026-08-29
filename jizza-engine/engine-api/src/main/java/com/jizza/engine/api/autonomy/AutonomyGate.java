package com.jizza.engine.api.autonomy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Consulted before every tool call and every autonomous run (R41). */
public interface AutonomyGate {

  Decision evaluate(UUID agentId, String toolRef, JsonNode args);

  /** Whether an individual agent is enabled at all (R40). */
  boolean agentEnabled(UUID agentId);
}
