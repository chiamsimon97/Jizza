package com.jizza.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * An agent on the user's team.
 *
 * @param custom          created by the user rather than seeded with the account
 * @param description     "when to use me", read by its manager when choosing who to hand work to
 * @param status          {@code draft}, {@code active} or {@code archived}; a paused agent is an
 *                        active one with {@code enabled} false
 * @param modelTier       {@code standard} or {@code deep}
 * @param memoryDomains   built-in memory domains it may read besides its own
 * @param parentAgentId   who it reports to; null only for Jizza
 * @param monthlyCostLimit spend after which it stops working unattended for the month; null for none
 */
public record AgentDto(UUID id, UUID userId, String key, String name, String domain,
                       String systemPrompt, boolean orchestrator, boolean enabled,
                       boolean custom, String description, String status, String modelTier,
                       List<String> memoryDomains, JsonNode avatar, Double monthlyCostLimit,
                       UUID parentAgentId, List<String> responsibilities, String handsOffTo,
                       boolean memoryFromManager, UUID originTemplateId,
                       Integer originTemplateVersion) {

  public AgentDto(UUID id, UUID userId, String key, String name, String domain,
                  String systemPrompt, boolean orchestrator, boolean enabled) {
    this(id, userId, key, name, domain, systemPrompt, orchestrator, enabled, false, null,
        "active", "standard", List.of(), null, null, null, List.of(), null, true, null, null);
  }

  public boolean active() { return "active".equals(status); }
}
