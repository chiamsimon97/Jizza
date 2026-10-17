package com.jizza.engine.persistence.entity;

import com.jizza.engine.api.enums.MembershipStatus;
import com.jizza.engine.api.enums.WorkspaceRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A person's membership in a workspace, and their role there. */
@Entity
@Table(name = "workspace_members")
public class WorkspaceMemberEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID workspaceId;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private WorkspaceRole role;

  @Column(nullable = false)
  private MembershipStatus status = MembershipStatus.ACTIVE;

  private Instant joinedAt;

  @Version
  private long version;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getWorkspaceId() { return workspaceId; }
  public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public WorkspaceRole getRole() { return role; }
  public void setRole(WorkspaceRole role) { this.role = role; }

  public MembershipStatus getStatus() { return status; }
  public void setStatus(MembershipStatus status) { this.status = status; }

  public Instant getJoinedAt() { return joinedAt; }
  public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }

  public long getVersion() { return version; }
  public void setVersion(long version) { this.version = version; }

  @PrePersist
  void onCreate() {
    if (joinedAt == null) joinedAt = Instant.now();
  }
}
