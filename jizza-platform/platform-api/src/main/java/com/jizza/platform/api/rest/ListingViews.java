package com.jizza.platform.api.rest;

import com.jizza.engine.api.workspace.PublicListingStore;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.listing.ListingTransitions;
import java.util.List;

/** Maps listing records to their HTTP shapes, so every route words them the same way. */
final class ListingViews {

  private ListingViews() {}

  static Responses.Listing listing(PublicListingStore.Listing l,
                                   List<PublicListingStore.Review> history,
                                   boolean changesSinceApproval, Long submissionsLeftToday) {
    return new Responses.Listing(l.workspaceId(), l.workspaceName(), l.status(),
        profile(l.draft()), profile(l.approved()),
        ListingTransitions.visible(l.status(), l.approved() != null), changesSinceApproval,
        l.submittedAt(), l.listedAt(), history.stream().map(ListingViews::review).toList(),
        submissionsLeftToday);
  }

  static Responses.ListingProfile profile(PublicListingStore.Profile p) {
    return p == null ? null : new Responses.ListingProfile(p.displayName(), p.description(),
        p.category(), p.logoUrl(), p.website());
  }

  static Responses.ListingReview review(PublicListingStore.Review r) {
    return new Responses.ListingReview(r.id(), r.action(), r.actorEmail(), r.note(), r.createdAt());
  }

  static Responses.JoinRequest joinRequest(PublicListingStore.JoinRequest r) {
    return r == null ? null : new Responses.JoinRequest(r.id(), r.workspaceId(), r.workspaceName(),
        r.email(), r.message(), r.status(), r.createdAt(), r.decidedAt());
  }

  static boolean changed(PublicListingStore.Listing l) {
    return l.approved() != null && l.draft() != null && !l.approved().equals(l.draft());
  }
}
