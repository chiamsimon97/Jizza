package com.jizza.platform.api.rest;

import com.jizza.engine.api.enums.ListingStatus;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.listing.ListingReviewUseCase;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Super Admin's Listing reviews queue. Platform-wide rather than about the active account, so
 * the check is the caller's platform role (in a two-step session), made in the use case.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/admin/listings")
public class AdminListingController {

  private final ListingReviewUseCase reviews;

  public AdminListingController(ListingReviewUseCase reviews) {
    this.reviews = reviews;
  }

  @GetMapping
  public List<Responses.Listing> queue(
      @RequestParam(defaultValue = "PENDING") ListingStatus status) {
    return reviews.queue(CurrentUser.userId(), status).stream()
        .map(l -> ListingViews.listing(l, List.of(), ListingViews.changed(l), null)).toList();
  }

  @GetMapping("/{id}")
  public Responses.Listing item(@PathVariable UUID id) {
    return view(reviews.item(CurrentUser.userId(), id));
  }

  @PostMapping("/{id}/approve")
  public Responses.Listing approve(@PathVariable UUID id,
                                   @Valid @RequestBody(required = false) Requests.ReviewNote body) {
    return view(reviews.approve(CurrentUser.userId(), id, note(body)));
  }

  @PostMapping("/{id}/reject")
  public Responses.Listing reject(@PathVariable UUID id,
                                  @Valid @RequestBody(required = false) Requests.ReviewNote body) {
    return view(reviews.reject(CurrentUser.userId(), id, note(body)));
  }

  @PostMapping("/{id}/suspend")
  public Responses.Listing suspend(@PathVariable UUID id,
                                   @Valid @RequestBody(required = false) Requests.ReviewNote body) {
    return view(reviews.suspend(CurrentUser.userId(), id, note(body)));
  }

  @PostMapping("/{id}/reinstate")
  public Responses.Listing reinstate(@PathVariable UUID id,
                                     @Valid @RequestBody(required = false) Requests.ReviewNote body) {
    return view(reviews.reinstate(CurrentUser.userId(), id, note(body)));
  }

  private static String note(Requests.ReviewNote body) {
    return body == null ? null : body.note();
  }

  private static Responses.Listing view(ListingReviewUseCase.Item item) {
    return ListingViews.listing(item.listing(), item.history(), ListingViews.changed(item.listing()),
        null);
  }
}
