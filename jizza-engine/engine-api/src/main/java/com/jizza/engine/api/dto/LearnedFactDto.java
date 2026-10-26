package com.jizza.engine.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A long-term memory learned from something said in one conversation: in a thread, that thread's
 * own memory, shown beside its guide.
 *
 * @param confirmed the user confirmed it, rather than Jizza inferring it
 */
public record LearnedFactDto(UUID id, String content, String category, boolean confirmed,
                             Instant createdAt) {}
