package com.jizza.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.AutonomyRule;
import java.util.UUID;

/** @param agentId null means the rule is global for the user. */
public record AutonomyRuleDto(UUID id, UUID userId, UUID agentId, String toolRef,
                              AutonomyRule rule, JsonNode limits, int precedence) {}
