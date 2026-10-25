package com.jizza.engine.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A thread's learning file: Markdown that says what the thread is for and what it has learned.
 * It is read at the start of every turn in the thread and kept up to date after each one.
 *
 * @param source {@code jizza} while Jizza keeps it, {@code user} once the user edited it by hand
 */
public record ThreadGuideDto(UUID conversationId, String title, String topic,
                             ConversationSummaryDto.AgentRef lead, String markdown,
                             Instant updatedAt, String source) {}
