package com.jizza.engine.api.dto;

import java.time.Instant;
import java.util.UUID;

/** A conversation as the conversation list shows it. */
public record ConversationSummaryDto(UUID id, String title, String preview, Instant createdAt,
                                     Instant lastMessageAt, boolean pinned, boolean archived,
                                     int messageCount) {}
