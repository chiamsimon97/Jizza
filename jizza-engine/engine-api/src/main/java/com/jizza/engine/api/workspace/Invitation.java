package com.jizza.engine.api.workspace;

import com.jizza.engine.api.enums.InvitationStatus;
import com.jizza.engine.api.enums.WorkspaceRole;
import java.time.Instant;
import java.util.UUID;

/** A membership request, as stored. */
public record Invitation(UUID id, UUID workspaceId, String workspaceName, String email,
                         UUID inviteeUserId, WorkspaceRole role, InvitationStatus status,
                         UUID invitedByUserId, String invitedByEmail, Instant createdAt,
                         Instant expiresAt, Instant respondedAt) {

  public boolean pending() { return status == InvitationStatus.PENDING; }

  public boolean expired(Instant now) { return pending() && now.isAfter(expiresAt); }
}
