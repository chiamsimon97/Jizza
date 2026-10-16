package com.jizza.engine.persistence.repository;

import com.jizza.engine.api.enums.InvitationStatus;
import com.jizza.engine.persistence.entity.WorkspaceInvitationEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitationEntity, UUID> {

  List<WorkspaceInvitationEntity> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

  Optional<WorkspaceInvitationEntity> findFirstByWorkspaceIdAndEmailIgnoreCaseAndStatus(
      UUID workspaceId, String email, InvitationStatus status);

  Optional<WorkspaceInvitationEntity> findFirstBySignupTokenHashAndStatus(String tokenHash,
                                                                         InvitationStatus status);

  @Query("select i from WorkspaceInvitationEntity i where i.status = :status "
      + "and (i.inviteeUserId = :userId or lower(i.email) = lower(:email)) order by i.createdAt desc")
  List<WorkspaceInvitationEntity> findPendingFor(@Param("userId") UUID userId,
                                                 @Param("email") String email,
                                                 @Param("status") InvitationStatus status);

  @Modifying
  @Query("update WorkspaceInvitationEntity i set i.inviteeUserId = :userId "
      + "where lower(i.email) = lower(:email) and i.status = :status and i.inviteeUserId is null")
  int linkPending(@Param("email") String email, @Param("userId") UUID userId,
                  @Param("status") InvitationStatus status);

  @Modifying
  @Query("update WorkspaceInvitationEntity i set i.status = :expired "
      + "where i.status = :pending and i.expiresAt < :now")
  int expireOverdue(@Param("now") Instant now, @Param("pending") InvitationStatus pending,
                    @Param("expired") InvitationStatus expired);
}
