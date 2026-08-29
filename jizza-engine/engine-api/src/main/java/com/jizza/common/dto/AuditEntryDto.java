package com.jizza.common.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.ActorType;
import java.time.Instant;
import java.util.UUID;

public record AuditEntryDto(UUID id, ActorType actorType, UUID actorId, String actorName,
                            String action, String target, JsonNode detail,
                            String requestId, String hash, Instant createdAt) {}
