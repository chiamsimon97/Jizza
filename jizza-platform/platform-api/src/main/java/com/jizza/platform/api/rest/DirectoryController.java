package com.jizza.platform.api.rest;

import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.listing.DirectoryUseCase;
import com.jizza.platform.usecase.listing.JoinRequestUseCase;
import com.jizza.platform.usecase.listing.PublicListingUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Discover page: organizations a Super Admin approved for listing, for any signed-in person,
 * and asking to join one. Returns profile fields only, never members or anything private.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/directory")
public class DirectoryController {

  private final DirectoryUseCase directory;
  private final JoinRequestUseCase joinRequests;

  public DirectoryController(DirectoryUseCase directory, JoinRequestUseCase joinRequests) {
    this.directory = directory;
    this.joinRequests = joinRequests;
  }

  @GetMapping
  public Responses.DirectoryPage search(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(defaultValue = "0") int page) {
    var found = directory.search(q, category, page);
    return new Responses.DirectoryPage(found.items().stream()
        .map(e -> new Responses.DirectoryEntry(e.id(), ListingViews.profile(e.profile()),
            e.listedAt()))
        .toList(), found.total(), Math.max(0, page), DirectoryUseCase.PAGE_SIZE);
  }

  @GetMapping("/categories")
  public Responses.ListingCategories categories() {
    return new Responses.ListingCategories(PublicListingUseCase.CATEGORIES);
  }

  @GetMapping("/{id}")
  public Responses.DirectoryProfile profile(@PathVariable UUID id) {
    var v = directory.profile(CurrentUser.userId(), id);
    return new Responses.DirectoryProfile(v.entry().id(), ListingViews.profile(v.entry().profile()),
        v.entry().listedAt(), v.member(), ListingViews.joinRequest(v.pendingRequest()));
  }

  @PostMapping("/{id}/join-request")
  public Responses.JoinRequest askToJoin(@PathVariable UUID id,
                                         @Valid @RequestBody(required = false) Requests.AskToJoin body) {
    return ListingViews.joinRequest(joinRequests.request(CurrentUser.userId(), id,
        body == null ? null : body.message()));
  }

  @DeleteMapping("/{id}/join-request")
  public Responses.Message cancel(@PathVariable UUID id) {
    joinRequests.cancel(CurrentUser.userId(), id);
    return new Responses.Message("Request withdrawn.");
  }
}
