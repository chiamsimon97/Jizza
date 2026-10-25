package com.jizza.platform.usecase.listing;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.ListingAction;
import com.jizza.engine.api.enums.ListingStatus;
import com.jizza.engine.api.enums.WorkspaceType;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.PublicListingStore;
import com.jizza.engine.api.workspace.PublicListingStore.Listing;
import com.jizza.engine.api.workspace.PublicListingStore.Profile;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes sure the platform's own Public organization exists on every startup: an organization (never
 * a personal account) owned by the Super Admin and listed on Discover.
 *
 * <p>When it is missing, it is created for the Super Admin's account. If that is not possible
 * either, because no Super Admin email is set or nobody has registered with it, startup is
 * refused, unless {@code jizza.development} is true, in which case a warning is logged.
 */
@Component
public class PublicOrganizationInitializer {

  private static final Logger log = LoggerFactory.getLogger(PublicOrganizationInitializer.class);

  private final PublicListingStore store;
  private final WorkspaceDirectory directory;
  private final AuditLog audit;
  private final String superAdminEmail;
  private final String name;
  private final String description;
  private final boolean development;

  public PublicOrganizationInitializer(PublicListingStore store, WorkspaceDirectory directory,
      AuditLog audit,
      @Value("${jizza.security.super-admin.email:}") String superAdminEmail,
      @Value("${jizza.public-organization.name:Jizza Public}") String name,
      @Value("${jizza.public-organization.description:Jizza's public organization. Everyone on "
          + "Jizza can find it and ask to join.}") String description,
      @Value("${jizza.development:false}") boolean development) {
    this.store = store;
    this.directory = directory;
    this.audit = audit;
    this.superAdminEmail = superAdminEmail == null ? "" : superAdminEmail.trim();
    this.name = name.trim();
    this.description = description.trim();
    this.development = development;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(Ordered.HIGHEST_PRECEDENCE + 10)
  @Transactional
  public void ensure() {
    Optional<UUID> existing = store.platformOrganization().or(this::create);
    if (existing.isEmpty()) {
      String why = superAdminEmail.isEmpty()
          ? "no Super Admin email is set (jizza.security.super-admin.email)"
          : "no account is registered with the Super Admin email " + superAdminEmail;
      if (!development) {
        throw new IllegalStateException("The Public organization is missing and can't be created: "
            + why + ". Set jizza.development=true to start without it.");
      }
      log.warn("Starting without the Public organization because {} (development mode)", why);
      return;
    }
    keepListed(existing.get());
  }

  /** Refuses to hide the platform's Public organization from Discover. */
  static void refuseIfPlatform(PublicListingStore store, UUID workspaceId) {
    if (store.platformOrganization().filter(workspaceId::equals).isPresent()) {
      throw new JizzaException(HttpStatus.CONFLICT, "platform-organization",
          "Public organization",
          "This is Jizza's Public organization, so it always stays listed on Discover.");
    }
  }

  private Optional<UUID> create() {
    if (superAdminEmail.isEmpty()) return Optional.empty();
    Optional<UUID> owner = directory.userIdByEmail(superAdminEmail);
    if (owner.isEmpty()) return Optional.empty();
    UUID ownerId = owner.get();
    var ws = directory.createOrganization(ownerId, name);
    store.markPlatformOrganization(ws.id());
    audit.record(ownerId, ActorType.SYSTEM, null, "workspace.created", ws.name(),
        Json.obj().put("workspaceId", ws.id().toString())
            .put("type", WorkspaceType.ORGANIZATION.name()).put("platformPublic", true));
    log.warn("Created the Public organization \"{}\" for {}", ws.name(), superAdminEmail);
    return Optional.of(ws.id());
  }

  /** Publishes it on Discover if it is not listed, as the startup approval. */
  private void keepListed(UUID workspaceId) {
    Listing listing = store.listing(workspaceId).orElseThrow();
    boolean listed = listing.approved() != null
        && (listing.status() == ListingStatus.APPROVED || listing.status() == ListingStatus.PENDING);
    if (listed) return;
    Profile profile = listing.draft() != null
        ? listing.draft()
        : new Profile(listing.workspaceName(), description, "other", null, null);
    if (listing.draft() == null) store.saveDraft(workspaceId, profile);
    store.publish(workspaceId, profile,
        listing.listedAt() == null ? Instant.now() : listing.listedAt());
    store.setStatus(workspaceId, ListingStatus.APPROVED);
    store.recordReview(workspaceId, ListingAction.APPROVED, null,
        "Listed at startup as Jizza's Public organization");
    log.info("Listed the Public organization \"{}\" on Discover", listing.workspaceName());
  }
}
