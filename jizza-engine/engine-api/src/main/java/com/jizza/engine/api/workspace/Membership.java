package com.jizza.engine.api.workspace;

import com.jizza.engine.api.enums.MembershipStatus;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import java.time.Instant;
import java.util.UUID;

/** A person's membership in a workspace, with the workspace's type and name. */
public record Membership(UUID id, UUID workspaceId, WorkspaceType workspaceType, String workspaceName,
                         UUID userId, String email, WorkspaceRole role, MembershipStatus status,
                         Instant joinedAt) {

  public boolean active() { return status == MembershipStatus.ACTIVE; }

  public WorkspaceSummary summary() {
    return new WorkspaceSummary(workspaceId, workspaceType, workspaceName, role);
  }
}
