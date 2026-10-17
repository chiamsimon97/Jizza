package com.jizza.engine.api.autonomy;

import com.jizza.engine.api.enums.ActionType;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the guardrails learn what a tool outside the built-ins and the generated connector tools
 * does, such as a tool served by an MCP server.
 *
 * <p>The answer must come from something the user configured, never from what the tool says about
 * itself: a remote server describing its own tool as read-only is a claim, not a fact. A source
 * that does not know a tool answers empty, and the tool is then judged the most consequential kind.
 */
public interface ActionTypeSource {

  Optional<ActionType> actionTypeOf(UUID agentId, String toolRef);
}
