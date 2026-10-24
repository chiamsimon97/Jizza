package com.jizza.platform.usecase.listing;

import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.workspace.PublicListingStore;
import com.jizza.engine.api.workspace.PublicListingStore.JoinRequest;
import com.jizza.engine.api.workspace.PublicListingStore.Listing;
import com.jizza.engine.api.workspace.PublicListingStore.Page;
import com.jizza.engine.api.workspace.PublicListingStore.Profile;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Discover page: approved organizations, for any signed-in person. Only the approved snapshot
 * of a profile ever leaves here, never members, emails or anything the organization keeps.
 * Anything not listed is reported as missing, whatever its status.
 */
@Service
public class DirectoryUseCase {

  public static final int PAGE_SIZE = 24;

  /** One organization as others see it. */
  public record Entry(UUID id, Profile profile, Instant listedAt) {}

  /** A profile page, with where the caller stands with the organization. */
  public record ProfileView(Entry entry, boolean member, JoinRequest pendingRequest) {}

  private final PublicListingStore store;
  private final WorkspaceDirectory directory;

  public DirectoryUseCase(PublicListingStore store, WorkspaceDirectory directory) {
    this.store = store;
    this.directory = directory;
  }

  @Transactional(readOnly = true)
  public Page<Entry> search(String query, String category, int page) {
    String q = query == null ? null : query.trim();
    if (q != null && q.length() > 100) q = q.substring(0, 100);
    int p = Math.max(0, page);
    var found = store.searchListed(q, category, p * PAGE_SIZE, PAGE_SIZE);
    return new Page<>(found.items().stream().map(DirectoryUseCase::entry).toList(), found.total());
  }

  @Transactional(readOnly = true)
  public ProfileView profile(UUID userId, UUID workspaceId) {
    Listing listing = listed(store, workspaceId);
    boolean member = directory.membership(workspaceId, userId).isPresent();
    JoinRequest pending = member ? null
        : store.pendingJoinRequest(workspaceId, userId).orElse(null);
    return new ProfileView(entry(listing), member, pending);
  }

  /** The organization if others can see it, else 404. */
  static Listing listed(PublicListingStore store, UUID workspaceId) {
    return store.listing(workspaceId)
        .filter(l -> ListingTransitions.visible(l.status(), l.approved() != null))
        .orElseThrow(() -> new NotFoundException("Organization", workspaceId));
  }

  private static Entry entry(Listing l) {
    return new Entry(l.workspaceId(), l.approved(), l.listedAt());
  }
}
