package com.jizza.engine.security.workspace;

import com.jizza.engine.api.enums.InvitationStatus;
import com.jizza.engine.api.enums.MembershipStatus;
import com.jizza.engine.api.enums.PlatformRole;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.workspace.Invitation;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.engine.persistence.entity.UserEntity;
import com.jizza.engine.persistence.entity.WorkspaceEntity;
import com.jizza.engine.persistence.entity.WorkspaceInvitationEntity;
import com.jizza.engine.persistence.entity.WorkspaceMemberEntity;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.persistence.repository.WorkspaceInvitationRepository;
import com.jizza.engine.persistence.repository.WorkspaceMemberRepository;
import com.jizza.engine.persistence.repository.WorkspaceRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** JPA-backed {@link WorkspaceDirectory}. Stores what it is told; the rules live in the platform. */
@Component
public class JpaWorkspaceDirectory implements WorkspaceDirectory {

  static final String PERSONAL_NAME = "Personal";

  private final WorkspaceRepository workspaces;
  private final WorkspaceMemberRepository members;
  private final WorkspaceInvitationRepository invitations;
  private final UserRepository users;

  public JpaWorkspaceDirectory(WorkspaceRepository workspaces, WorkspaceMemberRepository members,
                               WorkspaceInvitationRepository invitations, UserRepository users) {
    this.workspaces = workspaces;
    this.members = members;
    this.invitations = invitations;
    this.users = users;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Workspace> workspace(UUID workspaceId) {
    return workspaces.findById(workspaceId).map(JpaWorkspaceDirectory::toWorkspace);
  }

  @Override
  @Transactional
  public UUID personalWorkspaceOf(UUID userId) {
    return workspaces.findFirstByCreatedByUserIdAndType(userId, WorkspaceType.PERSONAL)
        .map(WorkspaceEntity::getId)
        .orElseGet(() -> {
          WorkspaceEntity w = create(WorkspaceType.PERSONAL, PERSONAL_NAME, userId);
          addMember(w.getId(), userId, WorkspaceRole.OWNER);
          return w.getId();
        });
  }

  @Override
  @Transactional
  public Workspace createOrganization(UUID ownerUserId, String name) {
    WorkspaceEntity w = create(WorkspaceType.ORGANIZATION, name, ownerUserId);
    addMember(w.getId(), ownerUserId, WorkspaceRole.OWNER);
    return toWorkspace(w);
  }

  private WorkspaceEntity create(WorkspaceType type, String name, UUID createdBy) {
    WorkspaceEntity w = new WorkspaceEntity();
    w.setType(type);
    w.setName(name);
    w.setCreatedByUserId(createdBy);
    return workspaces.saveAndFlush(w);
  }

  // ── Memberships ───────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public List<Membership> membershipsOf(UUID userId) {
    String email = emailOf(userId).orElse(null);
    return members.findByUserId(userId).stream()
        .map(m -> toMembership(m, email))
        .flatMap(Optional::stream)
        .sorted(Comparator.comparing((Membership m) -> m.workspaceType() != WorkspaceType.PERSONAL)
            .thenComparing(m -> m.workspaceName().toLowerCase(java.util.Locale.ROOT)))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Membership> membership(UUID workspaceId, UUID userId) {
    if (workspaceId == null || userId == null) return Optional.empty();
    return members.findByWorkspaceIdAndUserId(workspaceId, userId)
        .flatMap(m -> toMembership(m, null));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Membership> member(UUID memberId) {
    return members.findById(memberId).flatMap(m -> toMembership(m, null));
  }

  @Override
  @Transactional(readOnly = true)
  public List<Membership> members(UUID workspaceId) {
    return members.findByWorkspaceIdOrderByJoinedAtAsc(workspaceId).stream()
        .map(m -> toMembership(m, null))
        .flatMap(Optional::stream)
        .toList();
  }

  @Override
  @Transactional
  public Membership addMember(UUID workspaceId, UUID userId, WorkspaceRole role) {
    WorkspaceMemberEntity m = new WorkspaceMemberEntity();
    m.setWorkspaceId(workspaceId);
    m.setUserId(userId);
    m.setRole(role);
    m.setStatus(MembershipStatus.ACTIVE);
    return toMembership(members.saveAndFlush(m), null).orElseThrow();
  }

  @Override
  @Transactional
  public Membership changeRole(UUID memberId, WorkspaceRole role) {
    WorkspaceMemberEntity m = members.findById(memberId)
        .orElseThrow(() -> new NotFoundException("Member", memberId));
    m.setRole(role);
    return toMembership(members.saveAndFlush(m), null).orElseThrow();
  }

  @Override
  @Transactional
  public void removeMember(UUID memberId) {
    members.deleteById(memberId);
  }

  // ── People ────────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> userIdByEmail(String email) {
    if (email == null || email.isBlank()) return Optional.empty();
    return users.findByEmailIgnoreCase(email.trim()).map(UserEntity::getId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> emailOf(UUID userId) {
    return userId == null ? Optional.empty() : users.findById(userId).map(UserEntity::getEmail);
  }

  @Override
  @Transactional(readOnly = true)
  public PlatformRole platformRoleOf(UUID userId) {
    return users.findById(userId).map(UserEntity::getPlatformRole).orElse(PlatformRole.NONE);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> lastActiveWorkspace(UUID userId) {
    return users.findById(userId).map(UserEntity::getLastActiveWorkspaceId);
  }

  @Override
  @Transactional
  public void rememberActiveWorkspace(UUID userId, UUID workspaceId) {
    users.findById(userId).ifPresent(u -> {
      u.setLastActiveWorkspaceId(workspaceId);
      users.save(u);
    });
  }

  // ── Membership requests ───────────────────────────────────────────────────

  @Override
  @Transactional
  public Invitation createInvitation(UUID workspaceId, String email, UUID inviteeUserId,
                                     WorkspaceRole role, String signupTokenHash,
                                     UUID invitedByUserId, Instant expiresAt) {
    WorkspaceInvitationEntity i = new WorkspaceInvitationEntity();
    i.setWorkspaceId(workspaceId);
    i.setEmail(email);
    i.setInviteeUserId(inviteeUserId);
    i.setRole(role);
    i.setStatus(InvitationStatus.PENDING);
    i.setSignupTokenHash(signupTokenHash);
    i.setInvitedByUserId(invitedByUserId);
    i.setExpiresAt(expiresAt);
    return toInvitation(invitations.saveAndFlush(i));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Invitation> invitation(UUID invitationId) {
    return invitations.findById(invitationId).map(this::toInvitation);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Invitation> pendingInvitation(UUID workspaceId, String email) {
    return invitations.findFirstByWorkspaceIdAndEmailIgnoreCaseAndStatus(workspaceId, email,
        InvitationStatus.PENDING).map(this::toInvitation);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Invitation> pendingInvitationBySignupToken(String signupTokenHash) {
    return invitations.findFirstBySignupTokenHashAndStatus(signupTokenHash, InvitationStatus.PENDING)
        .map(this::toInvitation);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Invitation> invitations(UUID workspaceId) {
    return invitations.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
        .map(this::toInvitation).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<Invitation> pendingFor(UUID userId, String email) {
    return invitations.findPendingFor(userId, email == null ? "" : email, InvitationStatus.PENDING)
        .stream().map(this::toInvitation).toList();
  }

  @Override
  @Transactional
  public void setInvitationStatus(UUID invitationId, InvitationStatus status) {
    invitations.findById(invitationId).ifPresent(i -> {
      i.setStatus(status);
      if (status != InvitationStatus.PENDING) i.setRespondedAt(Instant.now());
      // A spent request's sign-up link must stop working.
      if (status != InvitationStatus.PENDING) i.setSignupTokenHash(null);
      invitations.save(i);
    });
  }

  @Override
  @Transactional
  public int linkPendingInvitations(String email, UUID userId) {
    return invitations.linkPending(email, userId, InvitationStatus.PENDING);
  }

  @Override
  @Transactional
  public int expireOverdue(Instant now) {
    return invitations.expireOverdue(now, InvitationStatus.PENDING, InvitationStatus.EXPIRED);
  }

  // ── Mapping ───────────────────────────────────────────────────────────────

  private static Workspace toWorkspace(WorkspaceEntity w) {
    return new Workspace(w.getId(), w.getType(), w.getName(), w.getCreatedByUserId());
  }

  private Optional<Membership> toMembership(WorkspaceMemberEntity m, String knownEmail) {
    return workspaces.findById(m.getWorkspaceId()).map(w -> new Membership(m.getId(),
        w.getId(), w.getType(), w.getName(), m.getUserId(),
        knownEmail != null ? knownEmail : emailOf(m.getUserId()).orElse(null),
        m.getRole(), m.getStatus(), m.getJoinedAt()));
  }

  private Invitation toInvitation(WorkspaceInvitationEntity i) {
    String workspaceName = workspaces.findById(i.getWorkspaceId()).map(WorkspaceEntity::getName)
        .orElse(null);
    return new Invitation(i.getId(), i.getWorkspaceId(), workspaceName, i.getEmail(),
        i.getInviteeUserId(), i.getRole(), i.getStatus(), i.getInvitedByUserId(),
        emailOf(i.getInvitedByUserId()).orElse(null), i.getCreatedAt(), i.getExpiresAt(),
        i.getRespondedAt());
  }
}
