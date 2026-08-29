package com.jizza.common.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record SkillDto(UUID id, UUID userId, UUID workflowId, int version, String name,
                       String description, JsonNode parameterSchema, boolean invocable) {}
