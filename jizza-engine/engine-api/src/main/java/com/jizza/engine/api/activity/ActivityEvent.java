package com.jizza.engine.api.activity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/**
 * One thing that happened in a job, as the user may see it.
 *
 * <p>Payloads never carry credentials, raw request headers or query strings. Tool output is
 * summarised, not copied, because it is untrusted and can be large.
 *
 * @param type      one of the {@link ActivityTypes} constants
 * @param agentId   the agent the event is about, when there is one
 * @param agentName that agent's display name, so the client needs no lookup
 */
public record ActivityEvent(String type, UUID agentId, String agentName, ObjectNode payload) {}
