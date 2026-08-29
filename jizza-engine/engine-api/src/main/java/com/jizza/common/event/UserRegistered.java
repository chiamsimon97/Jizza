package com.jizza.common.event;

import java.util.UUID;

/**
 * Published when an account is created. Modules that need per-user setup -- seeding agents, the
 * kill-switch row, the default autonomy policies -- listen for this rather than reaching into the
 * security module.
 */
public record UserRegistered(UUID userId, String email) {}
