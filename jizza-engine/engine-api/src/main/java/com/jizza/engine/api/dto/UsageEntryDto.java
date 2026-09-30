package com.jizza.engine.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UsageEntryDto(UUID id, String model, String purpose,
                            int inputTokens, int outputTokens,
                            BigDecimal costUnits, Instant createdAt) {}
