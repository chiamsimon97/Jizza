package com.jizza.platform.api.rest;

import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.RequiresPermission;
import com.jizza.engine.api.workspace.PublicListingStore;
import com.jizza.engine.security.session.CurrentWorkspace;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.listing.JoinRequestUseCase;
import com.jizza.platform.usecase.listing.PublicListingUseCase;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The active organization's public listing and the requests to join it. Owners and Admins only;
 * a listing becomes visible only once a Super Admin approves it.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/organization")
public class OrganizationListingController {

  private final PublicListingUseCase listings;
  private final JoinRequestUseCase joinRequests;

  public OrganizationListingController(PublicListingUseCase listings,
                                       JoinRequestUseCase joinRequests) {
    this.listings = listings;
    this.joinRequests = joinRequests;
  }

  @GetMapping("/listing")
  @RequiresPermission(Permissions.LISTING_MANAGE)
  public Responses.Listing listing() {
    return view(listings.view(CurrentWorkspace.access()));
  }

  @PutMapping("/listing")
  @RequiresPermission(Permissions.LISTING_MANAGE)
  public Responses.Listing save(@Valid @RequestBody Requests.ListingProfile body) {
    return view(listings.saveProfile(CurrentWorkspace.access(), new PublicListingStore.Profile(
        body.displayName(), body.description(), body.category(), body.logoUrl(), body.website())));
  }

  @PostMapping("/listing/submit")
  @RequiresPermission(Permissions.LISTING_MANAGE)
  public Responses.Listing submit() {
    return view(listings.submit(CurrentWorkspace.access()));
  }

  @PostMapping("/listing/withdraw")
  @RequiresPermission(Permissions.LISTING_MANAGE)
  public Responses.Listing withdraw() {
    return view(listings.withdraw(CurrentWorkspace.access()));
  }

  @PostMapping("/listing/unlist")
  @RequiresPermission(Permissions.LISTING_MANAGE)
  public Responses.Listing unlist() {
    return view(listings.unlist(CurrentWorkspace.access()));
  }

  @GetMapping("/join-requests")
  @RequiresPermission(Permissions.MEMBERS_INVITE)
  public List<Responses.JoinRequest> joinRequests() {
    return joinRequests.list(CurrentWorkspace.access()).stream()
        .map(ListingViews::joinRequest).toList();
  }

  @PostMapping("/join-requests/{id}/accept")
  @RequiresPermission(Permissions.MEMBERS_INVITE)
  public Responses.JoinRequest accept(@PathVariable UUID id) {
    return ListingViews.joinRequest(joinRequests.accept(CurrentWorkspace.access(), id));
  }

  @PostMapping("/join-requests/{id}/decline")
  @RequiresPermission(Permissions.MEMBERS_INVITE)
  public Responses.JoinRequest decline(@PathVariable UUID id) {
    return ListingViews.joinRequest(joinRequests.decline(CurrentWorkspace.access(), id));
  }

  private static Responses.Listing view(PublicListingUseCase.View v) {
    return ListingViews.listing(v.listing(), v.history(), v.changesSinceApproval(),
        v.submissionsLeftToday());
  }
}
