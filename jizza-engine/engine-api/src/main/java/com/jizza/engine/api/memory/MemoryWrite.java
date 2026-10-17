package com.jizza.engine.api.memory;

import com.jizza.engine.api.enums.MemoryWriteOutcome;
import java.util.UUID;

/**
 * Result of remembering something.
 *
 * @param memory   the fact as stored (for {@code DUPLICATE}, the existing fact it matched)
 * @param affected the older fact this write superseded or conflicts with, if any
 */
public record MemoryWrite(Memory memory, MemoryWriteOutcome outcome, UUID affected) {}
