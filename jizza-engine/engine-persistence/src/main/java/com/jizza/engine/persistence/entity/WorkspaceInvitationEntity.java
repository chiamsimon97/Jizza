package com.jizza.engine.persistence.entity;

import com.jizza.engine.api.enums.InvitationStatus;
import com.jizza.engine.api.enums.WorkspaceRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A membership request. The sign-up token is stored only as its SHA-256. */
@Entity
@Table(name = "workspace_invitations")
public class WorkspaceInvitationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID workspaceId;

  @Column(nullable = false, columnDefinition = "citext")
  private String email;

  private UUID inviteeUserId;

  @Column(nullable = false)
  private WorkspaceRole role;

  @Column(nullable = false)
  private InvitationStatus status = InvitationStatus.PENDING;

  @Column(columnDefinition = "text")
  private String signupTokenHash;

  @Column(nullable = false)
  private UUID invitedByUserId;

  private Instant createdAt;

  @Column(nullable = false)
  private Instant expiresAt;

  private Instant respondedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getWorkspaceId() { return workspaceId; }
  public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }

  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }

  public UUID getInviteeUserId() { return inviteeUserId; }
  public void setInviteeUserId(UUID inviteeUserId) { this.inviteeUserId = inviteeUserId; }

  public WorkspaceRole getRole() { return role; }
  public void setRole(WorkspaceRole role) { this.role = role; }

  public InvitationStatus getStatus() { return status; }
  public void setStatus(InvitationStatus status) { this.status = status; }

  public String getSignupTokenHash() { return signupTokenHash; }
  public void setSignupTokenHash(String signupTokenHash) { this.signupTokenHash = signupTokenHash; }

  public UUID getInvitedByUserId() { return invitedByUserId; }
  public void setInvitedByUserId(UUID invitedByUserId) { this.invitedByUserId = invitedByUserId; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

  public Instant getRespondedAt() { return respondedAt; }
  public void setRespondedAt(Instant respondedAt) { this.respondedAt = respondedAt; }

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }
}
