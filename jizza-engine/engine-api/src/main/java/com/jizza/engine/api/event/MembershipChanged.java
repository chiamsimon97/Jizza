package com.jizza.engine.api.event;

import java.util.UUID;

/**
 * A person's membership in a workspace ended or was suspended. Sessions acting in that workspace
 * fall back to the person's personal workspace.
 */
public record MembershipChanged(UUID workspaceId, UUID userId, Kind kind) {
  public enum Kind { REMOVED, LEFT, ROLE_CHANGED }
}
