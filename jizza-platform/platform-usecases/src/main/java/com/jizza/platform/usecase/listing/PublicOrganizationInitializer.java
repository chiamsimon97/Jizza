package com.jizza.platform.usecase.listing;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.AccountSource;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.ListingStatus;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import com.jizza.engine.api.event.UserRegistered;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.api.workspace.PublicListingStore;
import com.jizza.engine.api.workspace.PublicListingStore.Listing;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
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
 * Makes sure the platform's own Public organization exists on every startup. It is Jizza's back
 * office, not an organization people join: every Individual and Team account is registered in it
 * (with where it came from) for the Super Admin's analytics and approvals, and people are members
 * only when the Super Admin made them an Admin or Developer. It is never listed on Discover.
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
  private final boolean development;

  public PublicOrganizationInitializer(PublicListingStore store, WorkspaceDirectory directory,
      AuditLog audit,
      @Value("${jizza.security.super-admin.email:}") String superAdminEmail,
      @Value("${jizza.public-organization.name:Jizza Public}") String name,
      @Value("${jizza.development:false}") boolean development) {
    this.store = store;
    this.directory = directory;
    this.audit = audit;
    this.superAdminEmail = superAdminEmail == null ? "" : superAdminEmail.trim();
    this.name = name.trim();
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
    keepOffDiscover(existing.get());
    int added = store.registerMissingAccounts();
    if (added > 0) log.info("Registered {} account(s) in the Public organization", added);
  }

  /** Registers the accounts a sign-up created: the person's own, and a Team account if any. */
  @EventListener
  @Transactional
  public void onRegistered(UserRegistered event) {
    for (Membership m : directory.membershipsOf(event.userId())) {
      if (m.role() != WorkspaceRole.OWNER) continue;
      store.registerAccount(m.workspaceId(), m.workspaceType() == WorkspaceType.PERSONAL
          ? AccountSource.INDIVIDUAL_SIGNUP : AccountSource.ORGANIZATION_SIGNUP, event.userId());
    }
  }

  /** Whether {@code workspaceId} is the platform's Public organization. */
  public static boolean isPlatform(PublicListingStore store, UUID workspaceId) {
    return store.platformOrganization().filter(workspaceId::equals).isPresent();
  }

  /** Refuses listing changes for the Public organization, which is never on Discover. */
  static void refuseIfPlatform(PublicListingStore store, UUID workspaceId) {
    if (isPlatform(store, workspaceId)) {
      throw new JizzaException(HttpStatus.CONFLICT, "platform-organization",
          "Public organization",
          "Jizza Public is the platform's account registry, so it isn't listed on Discover.");
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

  /** Takes it off Discover if it was ever listed. */
  private void keepOffDiscover(UUID workspaceId) {
    Listing listing = store.listing(workspaceId).orElseThrow();
    if (listing.approved() == null && listing.status() == ListingStatus.NONE) return;
    store.publish(workspaceId, null, null);
    store.setStatus(workspaceId, ListingStatus.NONE);
    log.info("Took the Public organization \"{}\" off Discover", listing.workspaceName());
  }
}
