package com.jizza.engine.api.event;

import java.util.UUID;

/** The kill switch was thrown for a person; anything running for them should stop now. */
public record AutonomyPaused(UUID userId) {}
