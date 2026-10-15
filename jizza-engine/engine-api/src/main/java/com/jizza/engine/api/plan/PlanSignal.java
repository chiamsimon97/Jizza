package com.jizza.engine.api.plan;

import java.util.UUID;

/**
 * A specialist noticed something off while working on the user's behalf (a plan's condition would
 * break, a value is out of range, a step failed). It does not message the user: it tells Jizza, who
 * decides whether a standing instruction covers it or the user has to be asked.
 *
 * @param planId null when the signal is not about a known plan
 */
public record PlanSignal(UUID userId, UUID planId, UUID agentId, String agentName, String condition,
                         String evidence, String suggestedAction) {}
