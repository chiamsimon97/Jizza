package com.jizza.engine.api.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record JobEventDto(int seq, String type, UUID agentId, String agentName, JsonNode payload,
                          Instant at) {}
