package com.jizza.engine.api.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * What a custom agent is, as the platform hands it to the store after validating it. Every field
 * is written as given; a null list is stored as empty.
 */
public record CustomAgentDraft(String name, String description, String systemPrompt,
                               String modelTier, List<String> memoryDomains, JsonNode avatar,
                               Double monthlyCostLimit, UUID parentAgentId,
                               List<String> responsibilities, String handsOffTo,
                               boolean memoryFromManager, String status, UUID originTemplateId,
                               Integer originTemplateVersion) {}
