package com.jizza.engine.api.autonomy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.ActionType;
import java.util.UUID;

/**
 * Everything a guardrail can be written against for one tool call: who is acting, what they are
 * calling with which arguments, and, when it happens inside a workflow, which workflow version and
 * step.
 *
 * @param workflowId      the workflow the call runs inside, or null outside any workflow
 * @param workflowVersion the version of that workflow being run
 * @param nodeId          the step of that workflow
 * @param probe           true only when asking whether a tool could ever be offered to an agent;
 *                        conditions are then assumed satisfiable because there are no arguments
 *                        yet. A probe never authorises a call.
 * @param knownActionType what the engine itself already knows the tool does, when it called the
 *                        tool by id rather than by a name a model chose; null to look it up
 */
public record GuardrailContext(UUID agentId, String toolRef, JsonNode args, UUID workflowId,
                               Integer workflowVersion, String nodeId, boolean probe,
                               ActionType knownActionType) {

  public static GuardrailContext of(UUID agentId, String toolRef, JsonNode args) {
    GuardrailScope.Frame frame = GuardrailScope.current();
    return frame == null
        ? new GuardrailContext(agentId, toolRef, args, null, null, null, false, null)
        : new GuardrailContext(agentId, toolRef, args, frame.workflowId(), frame.workflowVersion(),
            frame.nodeId(), false, null);
  }

  public static GuardrailContext inWorkflow(UUID agentId, String toolRef, JsonNode args,
                                            UUID workflowId, int workflowVersion, String nodeId,
                                            ActionType knownActionType) {
    return new GuardrailContext(agentId, toolRef, args, workflowId, workflowVersion, nodeId, false,
        knownActionType);
  }

  public static GuardrailContext probe(UUID agentId, String toolRef) {
    GuardrailScope.Frame frame = GuardrailScope.current();
    return frame == null
        ? new GuardrailContext(agentId, toolRef, null, null, null, null, true, null)
        : new GuardrailContext(agentId, toolRef, null, frame.workflowId(), frame.workflowVersion(),
            frame.nodeId(), true, null);
  }

  /** The same call judged without the arguments being known, for "what would happen?". */
  public GuardrailContext withArgs(JsonNode newArgs) {
    return new GuardrailContext(agentId, toolRef, newArgs, workflowId, workflowVersion, nodeId,
        probe, knownActionType);
  }
}
