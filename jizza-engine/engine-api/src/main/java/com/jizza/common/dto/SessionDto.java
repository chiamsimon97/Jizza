package com.jizza.common.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionDto(UUID id, String device, String ip, Instant issuedAt,
                         Instant idleExpiresAt, Instant absoluteExpiresAt,
                         boolean revoked, boolean current) {}
