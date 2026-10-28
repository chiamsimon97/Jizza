package com.jizza.engine.webauto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.autonomy.AutonomyGate;
import com.jizza.engine.api.autonomy.Decision;
import com.jizza.engine.api.autonomy.GuardrailContext;
import com.jizza.engine.api.device.DeviceActionSpec;
import com.jizza.engine.api.device.DeviceDto;
import com.jizza.engine.api.error.AutonomyDeniedException;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import java.util.UUID;

/**
 * Judges a browser command a person starts at design time exactly as a chat tool would be judged,
 * so testing, picking and recording can never do what the guardrails would stop.
 */
final class WebGuardrails {

  private final AgentDirectory agents;
  private final AutonomyGate autonomy;

  WebGuardrails(AgentDirectory agents, AutonomyGate autonomy) {
    this.agents = agents;
    this.autonomy = autonomy;
  }

  /** @param what how the refusal names the action, such as "This test" */
  void judge(Access access, DeviceDto device, DeviceActionSpec spec, JsonNode inputs, String what) {
    UUID actor = agents.orchestrator(access.userId()).id();
    String toolRef = "device_" + device.id().toString().substring(0, 8) + "__"
        + spec.action().replaceAll("[^A-Za-z0-9]", "_");
    Decision decision = autonomy.decide(new GuardrailContext(actor, toolRef, inputs,
        null, null, null, false, spec.actionType()));
    if (decision.denied()) throw new AutonomyDeniedException(decision.reason());
    if (decision.needsPermission() || decision.needsJizza()) {
      throw new ConflictException(what + " needs approval under your guardrails: "
          + decision.reason());
    }
  }
}
