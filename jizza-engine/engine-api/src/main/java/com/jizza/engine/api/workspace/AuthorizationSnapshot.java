package com.jizza.engine.api.workspace;

import com.jizza.engine.api.enums.PlatformRole;
import com.jizza.engine.api.enums.WorkspaceRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What the client needs to shape itself: the active account, the caller's role and granted
 * permission names in it, the accounts they can switch to, and how many requests await them.
 */
public record AuthorizationSnapshot(UUID userId, String email, PlatformRole platformRole,
                                    WorkspaceSummary activeWorkspace, WorkspaceRole role,
                                    Set<String> permissions, Set<WorkspaceRole> assignableRoles,
                                    List<WorkspaceSummary> workspaces, long pendingInvitationCount,
                                    String permissionsVersion) {}
