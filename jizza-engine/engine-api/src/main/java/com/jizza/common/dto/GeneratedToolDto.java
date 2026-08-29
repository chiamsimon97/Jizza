package com.jizza.common.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record GeneratedToolDto(UUID id, UUID connectionId, String name, String description,
                               JsonNode inputSchema, String httpMethod, String pathTemplate,
                               JsonNode paramMapping, JsonNode authMapping,
                               boolean sideEffecting, boolean enabled) {}
