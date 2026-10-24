package com.jizza.platform.usecase.listing;

import com.jizza.engine.api.enums.ListingAction;
import com.jizza.engine.api.enums.ListingStatus;
import com.jizza.engine.api.error.JizzaException;
import org.springframework.http.HttpStatus;

/**
 * Which listing status changes are allowed, and where each one leads. The approved snapshot
 * matters: an organization that is already listed keeps its approved version visible while an
 * edit is in review, so a rejected or withdrawn edit leads back to Approved rather than to
 * Rejected or None.
 *
 * <pre>
 *   none / rejected ──submit──▶ pending ──approve──▶ approved ──unlist──▶ none
 *                                  │  └──reject──▶ rejected (approved, when already listed)
 *                                  └──withdraw──▶ none (approved, when already listed)
 *   approved ──submit (an edit)──▶ pending
 *   approved / pending ──suspend──▶ suspended ──reinstate──▶ approved (none, if never listed)
 * </pre>
 */
public final class ListingTransitions {

  private ListingTransitions() {}

  /**
   * The status {@code action} leads to from {@code from}, or a 409 naming why it is not allowed.
   *
   * @param listed whether the organization has an approved snapshot others can see
   */
  public static ListingStatus next(ListingStatus from, boolean listed, ListingAction action) {
    return switch (action) {
      case SUBMITTED -> switch (from) {
        case NONE, REJECTED, APPROVED -> ListingStatus.PENDING;
        case PENDING -> refuse("listing-pending", "Already in review",
            "This organization is already waiting for review.");
        case SUSPENDED -> refuse("listing-suspended", "Listing suspended",
            "This organization's listing is suspended. It can't be sent for review until it is "
                + "reinstated.");
      };
      case WITHDRAWN -> from == ListingStatus.PENDING
          ? (listed ? ListingStatus.APPROVED : ListingStatus.NONE)
          : refuse("listing-not-pending", "Nothing to withdraw", "There's no request in review.");
      case UNLISTED -> listed && (from == ListingStatus.APPROVED || from == ListingStatus.PENDING)
          ? ListingStatus.NONE
          : refuse("listing-not-listed", "Not listed", "This organization isn't listed.");
      case APPROVED -> from == ListingStatus.PENDING ? ListingStatus.APPROVED
          : refuse("listing-not-pending", "Nothing to approve", "This request isn't in review.");
      case REJECTED -> from == ListingStatus.PENDING
          ? (listed ? ListingStatus.APPROVED : ListingStatus.REJECTED)
          : refuse("listing-not-pending", "Nothing to reject", "This request isn't in review.");
      case SUSPENDED -> listed && (from == ListingStatus.APPROVED || from == ListingStatus.PENDING)
          ? ListingStatus.SUSPENDED
          : refuse("listing-not-listed", "Not listed", "Only a listed organization can be suspended.");
      case REINSTATED -> from == ListingStatus.SUSPENDED
          ? (listed ? ListingStatus.APPROVED : ListingStatus.NONE)
          : refuse("listing-not-suspended", "Not suspended", "This organization isn't suspended.");
    };
  }

  /** Whether other users see the organization on Discover. */
  public static boolean visible(ListingStatus status, boolean listed) {
    return listed && (status == ListingStatus.APPROVED || status == ListingStatus.PENDING);
  }

  private static ListingStatus refuse(String code, String title, String detail) {
    throw new JizzaException(HttpStatus.CONFLICT, code, title, detail);
  }
}
