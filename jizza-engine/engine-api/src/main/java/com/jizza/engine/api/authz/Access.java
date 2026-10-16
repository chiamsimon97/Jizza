package com.jizza.engine.api.authz;

import java.util.UUID;

/** Who is acting, and in which workspace (the caller's active account). */
public record Access(UUID userId, UUID workspaceId) {}
