package com.jizza.platform.api.rest.dto;

import com.jizza.engine.api.dto.AutonomyRuleDto;
import java.util.List;
import java.util.UUID;

public final class Responses {

  private Responses() {}

  /**
   * @param mfaRequired when true the client must call {@code /api/auth/mfa/verify} next.
   * @param enrollmentRequired signed in, but two-step sign-in must be set up before anything else.
   */
  public record LoginResult(boolean mfaRequired, String mfaToken, UUID userId,
                            boolean enrollmentRequired) {}

  public record MfaSetup(String secret, String provisioningUri) {}

  public record RecoveryCodes(List<String> codes) {}

  public record AuthorizeUrl(String url) {}

  public record TestConnection(boolean ok, int httpStatus, String message) {}

  public record Message(String message) {}

  /** Explicit rules plus the default that applies when none of them match. */
  public record AutonomySettings(List<AutonomyRuleDto> policies, String defaultRule,
                                 boolean autonomyPaused) {}

  public record ChainVerification(boolean intact, UUID firstBrokenEntryId, long entries) {}

  public record WebhookEndpoint(UUID id, String url, UUID workflowId, boolean enabled) {}

  /** What a sign-up link's invitation shows before the account exists. */
  public record InvitationPreview(String email, String workspaceName) {}

  public record Member(UUID id, UUID userId, String email,
                       com.jizza.engine.api.enums.WorkspaceRole role,
                       com.jizza.engine.api.enums.MembershipStatus status,
                       java.time.Instant joinedAt, boolean you) {}

  /** A membership request as the organization sees it. */
  public record Invitation(UUID id, String email, com.jizza.engine.api.enums.WorkspaceRole role,
                           com.jizza.engine.api.enums.InvitationStatus status,
                           String invitedByEmail, java.time.Instant createdAt,
                           java.time.Instant expiresAt, java.time.Instant respondedAt) {}

  /** A membership request as the invited person sees it. */
  public record ReceivedInvitation(UUID id, UUID workspaceId, String workspaceName,
                                   com.jizza.engine.api.enums.WorkspaceRole role,
                                   String invitedByEmail, java.time.Instant createdAt,
                                   java.time.Instant expiresAt) {}

  /** A permission's metadata from permissions.json, without who it is granted to. */
  public record Permission(String name, String label, String description, String category,
                           String module, List<String> dependencies) {}
}
