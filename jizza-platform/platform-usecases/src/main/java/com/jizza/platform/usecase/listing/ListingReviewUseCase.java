package com.jizza.platform.usecase.listing;

import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.enums.ListingAction;
import com.jizza.engine.api.enums.ListingStatus;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.workspace.PublicListingStore;
import com.jizza.engine.api.workspace.PublicListingStore.Listing;
import com.jizza.engine.api.workspace.PublicListingStore.Review;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Super Admin's Listing reviews queue: approving, rejecting, suspending and reinstating public
 * listings. Every call is refused unless the caller is the platform's super admin in a session
 * that passed two-step sign-in; an organization's own Owner cannot approve itself.
 */
@Service
public class ListingReviewUseCase {

  /** One queue entry: the listing and what happened to it so far. */
  public record Item(Listing listing, List<Review> history) {}

  private final PublicListingStore store;
  private final PermissionEvaluator permissions;
  private final ListingNotices notices;

  public ListingReviewUseCase(PublicListingStore store, PermissionEvaluator permissions,
                              ListingNotices notices) {
    this.store = store;
    this.permissions = permissions;
    this.notices = notices;
  }

  @Transactional(readOnly = true)
  public List<Listing> queue(UUID userId, ListingStatus status) {
    requireSuperAdmin(userId);
    if (status == null || status == ListingStatus.NONE) {
      throw new ValidationException("Choose pending, approved, rejected or suspended");
    }
    return store.byStatus(status);
  }

  @Transactional(readOnly = true)
  public Item item(UUID userId, UUID workspaceId) {
    requireSuperAdmin(userId);
    Listing listing = listing(workspaceId);
    return new Item(listing, store.history(workspaceId));
  }

  /** Publishes the profile as it was sent. It shows on Discover from now on. */
  @Transactional
  public Item approve(UUID userId, UUID workspaceId, String note) {
    requireSuperAdmin(userId);
    Listing listing = listing(workspaceId);
    ListingStatus next = ListingTransitions.next(listing.status(), listing.approved() != null,
        ListingAction.APPROVED);
    store.publish(workspaceId, listing.draft(),
        listing.listedAt() == null ? Instant.now() : listing.listedAt());
    store.setStatus(workspaceId, next);
    String why = optional(note);
    store.recordReview(workspaceId, ListingAction.APPROVED, userId, why);
    notices.announce(userId, "listing.approved", workspaceId, listing.workspaceName(), why,
        notices.managers(workspaceId), listing.workspaceName() + "'s public listing was approved. "
            + "Everyone on Jizza can now find it on Discover.");
    return item(userId, workspaceId);
  }

  /** Sends the request back with a reason. A listed organization keeps its approved version. */
  @Transactional
  public Item reject(UUID userId, UUID workspaceId, String reason) {
    requireSuperAdmin(userId);
    Listing listing = listing(workspaceId);
    String why = required(reason);
    boolean listed = listing.approved() != null;
    ListingStatus next = ListingTransitions.next(listing.status(), listed, ListingAction.REJECTED);
    store.setStatus(workspaceId, next);
    store.recordReview(workspaceId, ListingAction.REJECTED, userId, why);
    notices.announce(userId, "listing.rejected", workspaceId, listing.workspaceName(), why,
        notices.managers(workspaceId), (listed
            ? "The changes to " + listing.workspaceName() + "'s public profile were not approved: "
            : listing.workspaceName() + "'s request to be listed publicly was not approved: ")
            + why);
    return item(userId, workspaceId);
  }

  /** Hides a listed organization until it is reinstated. */
  @Transactional
  public Item suspend(UUID userId, UUID workspaceId, String reason) {
    requireSuperAdmin(userId);
    Listing listing = listing(workspaceId);
    PublicOrganizationInitializer.refuseIfPlatform(store, workspaceId);
    String why = required(reason);
    ListingStatus next = ListingTransitions.next(listing.status(), listing.approved() != null,
        ListingAction.SUSPENDED);
    store.setStatus(workspaceId, next);
    store.recordReview(workspaceId, ListingAction.SUSPENDED, userId, why);
    notices.announce(userId, "listing.suspended", workspaceId, listing.workspaceName(), why,
        notices.managers(workspaceId), listing.workspaceName()
            + "'s public listing was suspended and is hidden from Discover: " + why);
    return item(userId, workspaceId);
  }

  @Transactional
  public Item reinstate(UUID userId, UUID workspaceId, String note) {
    requireSuperAdmin(userId);
    Listing listing = listing(workspaceId);
    ListingStatus next = ListingTransitions.next(listing.status(), listing.approved() != null,
        ListingAction.REINSTATED);
    store.setStatus(workspaceId, next);
    String why = optional(note);
    store.recordReview(workspaceId, ListingAction.REINSTATED, userId, why);
    notices.announce(userId, "listing.reinstated", workspaceId, listing.workspaceName(), why,
        notices.managers(workspaceId), next == ListingStatus.APPROVED
            ? listing.workspaceName() + "'s public listing was reinstated and shows on Discover again."
            : listing.workspaceName() + "'s listing suspension was lifted. It can be sent for "
                + "review again.");
    return item(userId, workspaceId);
  }

  // ── Rules ─────────────────────────────────────────────────────────────────

  /** Refused with 403 for everyone else, organization Owners included. */
  private void requireSuperAdmin(UUID userId) {
    if (!permissions.isSuperAdmin(userId)) {
      throw new JizzaException(HttpStatus.FORBIDDEN, "super-admin-only", "Super Admin only",
          "Only a Super Admin signed in with two-step verification can review listings.");
    }
  }

  private Listing listing(UUID workspaceId) {
    return store.listing(workspaceId)
        .filter(l -> l.draft() != null)
        .orElseThrow(() -> new NotFoundException("Listing", workspaceId));
  }

  private static String required(String reason) {
    String why = optional(reason);
    if (why == null) throw new ValidationException("Give a reason, so the organization can fix it");
    return why;
  }

  private static String optional(String note) {
    if (note == null || note.isBlank()) return null;
    String t = note.trim();
    if (t.length() > 1000) throw new ValidationException("Keep the note under 1000 characters");
    return t;
  }
}
