package com.jizza.engine.api.dto;

import java.util.UUID;

public record AgentDto(UUID id, UUID userId, String key, String name, String domain,
                       String systemPrompt, boolean orchestrator, boolean enabled) {}
