package com.jizza.common.dto;

import java.util.UUID;

public record AgentDto(UUID id, UUID userId, String key, String name, String domain,
                       String systemPrompt, boolean orchestrator, boolean enabled) {}
