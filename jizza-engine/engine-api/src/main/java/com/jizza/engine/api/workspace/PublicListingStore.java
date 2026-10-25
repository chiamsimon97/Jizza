package com.jizza.engine.api.workspace;

import com.jizza.engine.api.enums.AccountSource;
import com.jizza.engine.api.enums.JoinRequestStatus;
import com.jizza.engine.api.enums.ListingAction;
import com.jizza.engine.api.enums.ListingStatus;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for public organization listings, their review history and join requests. Policy-free:
 * which status changes are allowed, who may make them and who hears about it are decided in the
 * platform.
 */
public interface PublicListingStore {

  /** What an organization shows about itself. Never members, workflows or anything private. */
  record Profile(String displayName, String description, String category, String logoUrl,
                 String website) {}

  /**
   * An organization's listing. {@code draft} is what its Owner or Admin last saved, {@code approved}
   * the version other users see, or null when it has none.
   */
  record Listing(UUID workspaceId, String workspaceName, ListingStatus status, Profile draft,
                 Profile approved, Instant submittedAt, Instant listedAt, Instant updatedAt) {}

  record Review(UUID id, UUID workspaceId, ListingAction action, UUID actorUserId, String actorEmail,
                String note, Instant createdAt) {}

  record JoinRequest(UUID id, UUID workspaceId, String workspaceName, UUID userId, String email,
                     String message, JoinRequestStatus status, UUID decidedByUserId,
                     Instant createdAt, Instant decidedAt) {
    public boolean pending() { return status == JoinRequestStatus.PENDING; }
  }

  record Page<T>(List<T> items, long total) {}

  // ── Listings ──────────────────────────────────────────────────────────────

  /** The organization's listing; empty when the workspace does not exist. */
  Optional<Listing> listing(UUID workspaceId);

  void saveDraft(UUID workspaceId, Profile draft);

  void setStatus(UUID workspaceId, ListingStatus status);

  void markSubmitted(UUID workspaceId, Instant at);

  /** Makes {@code snapshot} the version others see, or hides the organization when null. */
  void publish(UUID workspaceId, Profile snapshot, Instant listedAt);

  void recordReview(UUID workspaceId, ListingAction action, UUID actorUserId, String note);

  /** Newest first. */
  List<Review> history(UUID workspaceId);

  long countReviews(UUID workspaceId, ListingAction action, Instant since);

  /** For the review queue, oldest submission first. */
  List<Listing> byStatus(ListingStatus status);

  /**
   * Organizations other users can see: those with an approved snapshot whose listing is approved,
   * or approved with an edit awaiting review. Matches the approved snapshot only.
   */
  Page<Listing> searchListed(String query, String category, int offset, int limit);

  List<UUID> superAdmins();

  /** The platform's own Public organization, when it has been set up. */
  Optional<UUID> platformOrganization();

  /** Makes the organization the platform's Public organization. There is only ever one. */
  void markPlatformOrganization(UUID workspaceId);

  // ── Account registry (Jizza Public) ───────────────────────────────────────

  /**
   * An Individual or Team account as the Super Admin sees it. {@code publicRole} is the owner's
   * role in the Public organization, or null when they are not a member (an ordinary User).
   */
  record RegisteredAccount(UUID workspaceId, String name, WorkspaceType type, UUID ownerUserId,
                           String ownerEmail, AccountSource source, Instant registeredAt,
                           WorkspaceRole publicRole) {}

  /** Records the account in the registry; does nothing when it is already there. */
  void registerAccount(UUID workspaceId, AccountSource source, UUID byUserId);

  /** Registers every account the registry is missing. Returns how many were added. */
  int registerMissingAccounts();

  /** Newest first, matching the account name or the owner's email. */
  List<RegisteredAccount> registeredAccounts(String query);

  // ── Join requests ─────────────────────────────────────────────────────────

  JoinRequest createJoinRequest(UUID workspaceId, UUID userId, String message);

  Optional<JoinRequest> joinRequest(UUID requestId);

  Optional<JoinRequest> pendingJoinRequest(UUID workspaceId, UUID userId);

  /** Newest first. */
  List<JoinRequest> joinRequests(UUID workspaceId);

  void setJoinRequestStatus(UUID requestId, JoinRequestStatus status, UUID decidedByUserId);
}
