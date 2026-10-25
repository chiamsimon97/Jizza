package com.jizza.platform.usecase.listing;

import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.enums.ListingAction;
import com.jizza.engine.api.enums.ListingStatus;
import com.jizza.engine.api.enums.WorkspaceType;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.RateLimitedException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.workspace.PublicListingStore;
import com.jizza.engine.api.workspace.PublicListingStore.Listing;
import com.jizza.engine.api.workspace.PublicListingStore.Profile;
import com.jizza.engine.api.workspace.PublicListingStore.Review;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organization's side of a public listing: its profile, sending it for review, withdrawing a
 * request and unlisting. Permission to call these ({@code listing_manage}, Owner and Admin) is
 * checked at the route; this class enforces the status rules in {@link ListingTransitions}.
 *
 * <p>Nothing here makes an organization visible. Only a Super Admin's approval does, and the
 * version people see is the approved snapshot, so an edit after approval waits for review.
 */
@Service
public class PublicListingUseCase {

  /** The categories an organization can list itself under. */
  public static final List<String> CATEGORIES = List.of("agency", "consulting", "education",
      "finance", "healthcare", "marketing", "nonprofit", "operations", "retail", "technology",
      "other");

  private static final Set<String> CATEGORY_SET = Set.copyOf(CATEGORIES);

  /** What the organization's page shows: its listing, and the reviewer's notes so far. */
  public record View(Listing listing, List<Review> history, boolean changesSinceApproval,
                     long submissionsLeftToday) {}

  private final PublicListingStore store;
  private final WorkspaceDirectory directory;
  private final ListingNotices notices;
  private final int submissionsPerDay;

  public PublicListingUseCase(PublicListingStore store, WorkspaceDirectory directory,
                              ListingNotices notices,
                              @Value("${jizza.listing.submissions-per-day:3}") int submissionsPerDay) {
    this.store = store;
    this.directory = directory;
    this.notices = notices;
    this.submissionsPerDay = submissionsPerDay;
  }

  @Transactional(readOnly = true)
  public View view(Access access) {
    Listing listing = organization(access);
    return view(listing);
  }

  /** Saves the profile. On a listed organization this changes nothing others see until approved. */
  @Transactional
  public View saveProfile(Access access, Profile input) {
    Listing listing = organization(access);
    if (listing.status() == ListingStatus.SUSPENDED) {
      throw new JizzaException(HttpStatus.CONFLICT, "listing-suspended", "Listing suspended",
          "This organization's listing is suspended, so its profile can't be changed.");
    }
    if (listing.status() == ListingStatus.PENDING) {
      throw new JizzaException(HttpStatus.CONFLICT, "listing-pending", "In review",
          "Withdraw the request in review before changing the profile.");
    }
    store.saveDraft(access.workspaceId(), clean(input));
    return view(organization(access));
  }

  @Transactional
  public View submit(Access access) {
    Listing listing = organization(access);
    if (listing.draft() == null) {
      throw new ValidationException("Fill in the public profile before sending it for review");
    }
    boolean listed = listing.approved() != null;
    ListingStatus next = ListingTransitions.next(listing.status(), listed, ListingAction.SUBMITTED);
    if (listed && listing.status() == ListingStatus.APPROVED
        && Objects.equals(listing.draft(), listing.approved())) {
      throw new JizzaException(HttpStatus.CONFLICT, "listing-unchanged", "Nothing changed",
          "The profile is the same as the approved one.");
    }
    Instant now = Instant.now();
    if (store.countReviews(access.workspaceId(), ListingAction.SUBMITTED,
        now.minus(Duration.ofDays(1))) >= submissionsPerDay) {
      throw new RateLimitedException("This organization has been sent for review "
          + submissionsPerDay + " times in the last day. Try again tomorrow.");
    }
    store.setStatus(access.workspaceId(), next);
    store.markSubmitted(access.workspaceId(), now);
    store.recordReview(access.workspaceId(), ListingAction.SUBMITTED, access.userId(), null);
    notices.announce(access.userId(), "listing.submitted", access.workspaceId(),
        listing.workspaceName(), null, store.superAdmins(),
        listing.draft().displayName() + (listed ? " sent changes to its public profile"
            : " asked to be listed publicly") + ". Open Listing reviews to approve or reject it.");
    return view(organization(access));
  }

  @Transactional
  public View withdraw(Access access) {
    Listing listing = organization(access);
    ListingStatus next = ListingTransitions.next(listing.status(), listing.approved() != null,
        ListingAction.WITHDRAWN);
    store.setStatus(access.workspaceId(), next);
    store.recordReview(access.workspaceId(), ListingAction.WITHDRAWN, access.userId(), null);
    notices.announce(access.userId(), "listing.withdrawn", access.workspaceId(),
        listing.workspaceName(), null, List.of(), null);
    return view(organization(access));
  }

  /** Takes the organization off Discover at once. Sending it again starts a new review. */
  @Transactional
  public View unlist(Access access) {
    Listing listing = organization(access);
    PublicOrganizationInitializer.refuseIfPlatform(store, access.workspaceId());
    ListingStatus next = ListingTransitions.next(listing.status(), listing.approved() != null,
        ListingAction.UNLISTED);
    store.setStatus(access.workspaceId(), next);
    store.publish(access.workspaceId(), null, null);
    store.recordReview(access.workspaceId(), ListingAction.UNLISTED, access.userId(), null);
    notices.announce(access.userId(), "listing.unlisted", access.workspaceId(),
        listing.workspaceName(), null, notices.managers(access.workspaceId()),
        listing.workspaceName() + " is no longer listed publicly.");
    return view(organization(access));
  }

  // ── Rules ─────────────────────────────────────────────────────────────────

  private Listing organization(Access access) {
    var ws = directory.workspace(access.workspaceId())
        .orElseThrow(() -> new NotFoundException("Account", access.workspaceId()));
    if (ws.type() != WorkspaceType.ORGANIZATION) {
      throw new JizzaException(HttpStatus.CONFLICT, "personal-workspace-no-listing",
          "Individual accounts can't be listed", "Only an organization can be listed publicly.");
    }
    return store.listing(access.workspaceId())
        .orElseThrow(() -> new NotFoundException("Account", access.workspaceId()));
  }

  private View view(Listing listing) {
    boolean changed = listing.approved() != null && listing.draft() != null
        && !Objects.equals(listing.draft(), listing.approved());
    long used = store.countReviews(listing.workspaceId(), ListingAction.SUBMITTED,
        Instant.now().minus(Duration.ofDays(1)));
    return new View(listing, store.history(listing.workspaceId()), changed,
        Math.max(0, submissionsPerDay - used));
  }

  /** Trims and checks the profile. Links must be https, so a profile can't carry script URLs. */
  static Profile clean(Profile input) {
    if (input == null) throw new ValidationException("Fill in the public profile");
    String name = trim(input.displayName());
    if (name == null || name.length() < 2 || name.length() > 100) {
      throw new ValidationException("The public name must be 2 to 100 characters");
    }
    String description = trim(input.description());
    if (description == null) description = "";
    if (description.length() > 500) {
      throw new ValidationException("The description can be at most 500 characters");
    }
    String category = trim(input.category());
    category = category == null ? "other" : category.toLowerCase(Locale.ROOT);
    if (!CATEGORY_SET.contains(category)) throw new ValidationException("Choose a category");
    return new Profile(name, description, category, link(input.logoUrl(), "logo"),
        link(input.website(), "website"));
  }

  private static String link(String raw, String what) {
    String value = trim(raw);
    if (value == null) return null;
    if (value.length() > 500) throw new ValidationException("The " + what + " link is too long");
    try {
      URI uri = new URI(value);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null) {
        throw new ValidationException("The " + what + " link must start with https://");
      }
    } catch (java.net.URISyntaxException e) {
      throw new ValidationException("The " + what + " link isn't a valid address");
    }
    return value;
  }

  private static String trim(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
