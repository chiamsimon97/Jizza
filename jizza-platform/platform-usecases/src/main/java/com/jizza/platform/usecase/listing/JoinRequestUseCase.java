package com.jizza.platform.usecase.listing;

import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.enums.JoinRequestStatus;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.workspace.PublicListingStore;
import com.jizza.engine.api.workspace.PublicListingStore.JoinRequest;
import com.jizza.engine.api.workspace.PublicListingStore.Listing;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asking to join a listed organization, and its Owner or Admin answering. Accepting adds the
 * person with the User role; anything more is a role change on the Members tab afterwards.
 */
@Service
public class JoinRequestUseCase {

  private final PublicListingStore store;
  private final WorkspaceDirectory directory;
  private final ListingNotices notices;

  public JoinRequestUseCase(PublicListingStore store, WorkspaceDirectory directory,
                            ListingNotices notices) {
    this.store = store;
    this.directory = directory;
    this.notices = notices;
  }

  // ── The person asking ─────────────────────────────────────────────────────

  @Transactional
  public JoinRequest request(UUID userId, UUID workspaceId, String rawMessage) {
    Listing listing = DirectoryUseCase.listed(store, workspaceId);
    if (directory.membership(workspaceId, userId).isPresent()) {
      throw new JizzaException(HttpStatus.CONFLICT, "already-member", "Already a member",
          "You're already a member of " + listing.approved().displayName() + ".");
    }
    if (store.pendingJoinRequest(workspaceId, userId).isPresent()) {
      throw new JizzaException(HttpStatus.CONFLICT, "join-request-pending", "Already asked",
          "Your request to join is waiting for an answer.");
    }
    String message = rawMessage == null || rawMessage.isBlank() ? null : rawMessage.trim();
    if (message != null && message.length() > 500) {
      throw new ValidationException("Keep the message under 500 characters");
    }
    JoinRequest created = store.createJoinRequest(workspaceId, userId, message);
    notices.announce(userId, "join_request.created", workspaceId, listing.workspaceName(), null,
        notices.managers(workspaceId), created.email() + " asked to join "
            + listing.workspaceName() + ". Open Members, then Join requests, to accept or decline.");
    return created;
  }

  @Transactional
  public void cancel(UUID userId, UUID workspaceId) {
    JoinRequest pending = store.pendingJoinRequest(workspaceId, userId)
        .orElseThrow(() -> new NotFoundException("Join request", workspaceId));
    store.setJoinRequestStatus(pending.id(), JoinRequestStatus.CANCELLED, userId);
    notices.announce(userId, "join_request.cancelled", workspaceId, pending.workspaceName(), null,
        List.of(), null);
  }

  // ── The organization ──────────────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<JoinRequest> list(Access access) {
    return store.joinRequests(access.workspaceId());
  }

  @Transactional
  public JoinRequest accept(Access access, UUID requestId) {
    JoinRequest r = pendingHere(access, requestId);
    if (directory.membership(r.workspaceId(), r.userId()).isEmpty()) {
      directory.addMember(r.workspaceId(), r.userId(), WorkspaceRole.USER);
    }
    store.setJoinRequestStatus(requestId, JoinRequestStatus.ACCEPTED, access.userId());
    notices.announce(access.userId(), "join_request.accepted", r.workspaceId(), r.workspaceName(),
        null, List.of(r.userId()), "Your request to join " + r.workspaceName()
            + " was accepted. Switch to it from the account menu.");
    return store.joinRequest(requestId).orElseThrow();
  }

  @Transactional
  public JoinRequest decline(Access access, UUID requestId) {
    JoinRequest r = pendingHere(access, requestId);
    store.setJoinRequestStatus(requestId, JoinRequestStatus.DECLINED, access.userId());
    notices.announce(access.userId(), "join_request.declined", r.workspaceId(), r.workspaceName(),
        null, List.of(r.userId()), "Your request to join " + r.workspaceName() + " was declined.");
    return store.joinRequest(requestId).orElseThrow();
  }

  /** A request to some other organization, or one already answered, is reported as missing. */
  private JoinRequest pendingHere(Access access, UUID requestId) {
    return store.joinRequest(requestId)
        .filter(r -> r.workspaceId().equals(access.workspaceId()))
        .filter(JoinRequest::pending)
        .orElseThrow(() -> new NotFoundException("Join request", requestId));
  }
}
