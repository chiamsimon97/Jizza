package com.jizza.engine.api.workspace;

import com.jizza.engine.api.enums.InvitationStatus;
import com.jizza.engine.api.enums.PlatformRole;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for workspaces, memberships and membership requests. Policy-free: who may invite whom,
 * what gets audited and who is notified are decisions made in the platform.
 */
public interface WorkspaceDirectory {

  record Workspace(UUID id, WorkspaceType type, String name, UUID createdByUserId) {}

  Optional<Workspace> workspace(UUID workspaceId);

  /** The person's personal workspace, created on the spot if somehow missing. */
  UUID personalWorkspaceOf(UUID userId);

  /** Creates an organization with {@code ownerUserId} as its owner. */
  Workspace createOrganization(UUID ownerUserId, String name);

  /** Creates a Family with {@code ownerUserId} as its owner (spec 4.4). */
  Workspace createFamily(UUID ownerUserId, String name);

  /** The Family this person created, if any: a person owns at most one. */
  Optional<Workspace> familyOwnedBy(UUID userId);

  /**
   * True when both are active members of one workspace of type Family (spec 4.4). Vita's sharing
   * and any later family work read the plan only through this.
   */
  boolean sameFamily(UUID userA, UUID userB);

  /** The Family both are active members of, if any. */
  Optional<UUID> sharedFamily(UUID userA, UUID userB);

  // ── Memberships ───────────────────────────────────────────────────────────

  List<Membership> membershipsOf(UUID userId);

  Optional<Membership> membership(UUID workspaceId, UUID userId);

  Optional<Membership> member(UUID memberId);

  List<Membership> members(UUID workspaceId);

  Membership addMember(UUID workspaceId, UUID userId, WorkspaceRole role);

  Membership changeRole(UUID memberId, WorkspaceRole role);

  void removeMember(UUID memberId);

  // ── People ────────────────────────────────────────────────────────────────

  Optional<UUID> userIdByEmail(String email);

  Optional<String> emailOf(UUID userId);

  PlatformRole platformRoleOf(UUID userId);

  Optional<UUID> lastActiveWorkspace(UUID userId);

  void rememberActiveWorkspace(UUID userId, UUID workspaceId);

  // ── Membership requests ───────────────────────────────────────────────────

  Invitation createInvitation(UUID workspaceId, String email, UUID inviteeUserId, WorkspaceRole role,
                              String signupTokenHash, UUID invitedByUserId, Instant expiresAt);

  Optional<Invitation> invitation(UUID invitationId);

  Optional<Invitation> pendingInvitation(UUID workspaceId, String email);

  Optional<Invitation> pendingInvitationBySignupToken(String signupTokenHash);

  /** Newest first. */
  List<Invitation> invitations(UUID workspaceId);

  /** Pending requests addressed to this person, by account or by email. */
  List<Invitation> pendingFor(UUID userId, String email);

  void setInvitationStatus(UUID invitationId, InvitationStatus status);

  /** Links pending requests sent to {@code email} to the account just registered with it. */
  int linkPendingInvitations(String email, UUID userId);

  /** Marks pending requests past their expiry as expired. */
  int expireOverdue(Instant now);
}
