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

  /** Every account registered in the platform's Public organization, for the Super Admin. */
  public record AccountRegistry(UUID workspaceId, String workspaceName,
                                java.util.List<RegisteredAccount> accounts) {}

  /** {@code publicRole} is the owner's role in the Public organization; User unless promoted. */
  public record RegisteredAccount(UUID workspaceId, String name,
                                  com.jizza.engine.api.enums.WorkspaceType type, UUID ownerUserId,
                                  String ownerEmail, com.jizza.engine.api.enums.AccountSource source,
                                  java.time.Instant registeredAt,
                                  com.jizza.engine.api.enums.WorkspaceRole publicRole) {}

  public record PublicRole(UUID userId, com.jizza.engine.api.enums.WorkspaceRole role) {}

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

  // ── Public organizations ──────────────────────────────────────────────────

  public record ListingProfile(String displayName, String description, String category,
                               String logoUrl, String website) {}

  public record ListingReview(UUID id, com.jizza.engine.api.enums.ListingAction action,
                              String actorEmail, String note, java.time.Instant createdAt) {}

  /**
   * An organization's listing as its Owner, Admin or a Super Admin sees it. {@code draft} is the
   * profile being edited; {@code approved} the version on Discover, or null.
   */
  public record Listing(UUID workspaceId, String workspaceName,
                        com.jizza.engine.api.enums.ListingStatus status, ListingProfile draft,
                        ListingProfile approved, boolean visible, boolean changesSinceApproval,
                        java.time.Instant submittedAt, java.time.Instant listedAt,
                        List<ListingReview> history, Long submissionsLeftToday) {}

  public record ListingCategories(List<String> categories) {}

  /** One organization on Discover: its approved profile and nothing else. */
  public record DirectoryEntry(UUID id, ListingProfile profile, java.time.Instant listedAt) {}

  public record DirectoryPage(List<DirectoryEntry> items, long total, int page, int pageSize) {}

  /** A profile page, with where the caller stands: a member, waiting on a request, or neither. */
  public record DirectoryProfile(UUID id, ListingProfile profile, java.time.Instant listedAt,
                                 boolean member, JoinRequest pendingRequest) {}

  public record JoinRequest(UUID id, UUID workspaceId, String workspaceName, String email,
                            String message, com.jizza.engine.api.enums.JoinRequestStatus status,
                            java.time.Instant createdAt, java.time.Instant decidedAt) {}
}
