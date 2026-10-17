package com.jizza.engine.api.autonomy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActionType;
import java.util.UUID;

/**
 * The guardrails (R41), consulted before every tool call and every autonomous run.
 *
 * <p>{@link #evaluate(GuardrailContext)} only reads the rules. {@link #decide(GuardrailContext)} is
 * what a caller about to run something uses: it also has Jizza review an action left to it and
 * spends from any budget the deciding rule carries, so two calls cannot both squeeze under it.
 */
public interface AutonomyGate {
  /** Matches every tool. */
  String ANY = "*";
  /** Matches tools that only read or only speak to the user. */
  String ANY_READ = "*:read";
  /** Matches tools that change something outside Jizza. */
  String ANY_SIDE_EFFECTING = "*:side-effecting";

  /** The full evaluation. Implementations that know nothing of workflows may leave the default. */
  default Decision evaluate(GuardrailContext context) {
    return evaluate(context.agentId(), context.toolRef(), context.args());
  }

  /** Evaluates, then resolves a {@code JIZZA} decision and reserves budget for an allowed call. */
  default Decision decide(GuardrailContext context) {
    return evaluate(context);
  }

  /** Outside any workflow, or inside the one the current thread's scope names. */
  Decision evaluate(UUID agentId, String toolRef, JsonNode args);

  /** What a tool does, as the guardrails judge it; unknown tools count as moving money. */
  default ActionType actionTypeOf(UUID agentId, String toolRef) {
    ActionType type = evaluate(GuardrailContext.probe(agentId, toolRef)).actionType();
    return type == null ? ActionType.MONEY : type;
  }

  /** Whether an individual agent is enabled at all (R40). */
  boolean agentEnabled(UUID agentId);
}
