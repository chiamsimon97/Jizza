package com.jizza.engine.api.authz;

import java.util.UUID;

/**
 * Decides whether a person may use a permission in a workspace. The workspace is always explicit,
 * so the same check serves HTTP requests (the active workspace) and agent or workflow runs, which
 * have no session (the workspace that owns the resource).
 */
public interface PermissionEvaluator {

  boolean isAllowed(UUID userId, UUID workspaceId, String permission);

  /** The caller's role, permissions and accounts, for the client. */
  com.jizza.engine.api.workspace.AuthorizationSnapshot snapshot(UUID userId, UUID workspaceId);

  /** Throws {@link com.jizza.engine.api.error.PermissionDeniedException} unless allowed. */
  default void require(UUID userId, UUID workspaceId, String permission) {
    if (!isAllowed(userId, workspaceId, permission)) {
      throw new com.jizza.engine.api.error.PermissionDeniedException(permission, null);
    }
  }
}
