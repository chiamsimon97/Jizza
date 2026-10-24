package com.jizza.platform.usecase.listing;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Audit entries and inbox messages for listing and join-request events. Each event goes on the
 * actor's audit chain and on the chain of everyone it tells; a notification that cannot be raised
 * never undoes the change itself.
 */
@Component
class ListingNotices {

  private static final Logger log = LoggerFactory.getLogger(ListingNotices.class);

  private final WorkspaceDirectory directory;
  private final AuditLog audit;
  private final ObjectProvider<NotificationBus> notifications;

  ListingNotices(WorkspaceDirectory directory, AuditLog audit,
                 ObjectProvider<NotificationBus> notifications) {
    this.directory = directory;
    this.audit = audit;
    this.notifications = notifications;
  }

  /** The organization's Owners and Admins: who hears about its listing and join requests. */
  Set<UUID> managers(UUID workspaceId) {
    Set<UUID> out = new LinkedHashSet<>();
    for (Membership m : directory.members(workspaceId)) {
      if (m.active() && (m.role() == WorkspaceRole.OWNER || m.role() == WorkspaceRole.ADMIN)) {
        out.add(m.userId());
      }
    }
    return out;
  }

  /** Records {@code event} for the actor and for each recipient, and tells the recipients. */
  void announce(UUID actor, String event, UUID workspaceId, String workspaceName, String note,
                Collection<UUID> recipients, String message) {
    var detail = Json.obj().put("workspaceId", workspaceId.toString());
    if (note != null) detail.put("note", note);
    Set<UUID> chains = new LinkedHashSet<>();
    chains.add(actor);
    chains.addAll(recipients);
    for (UUID user : chains) {
      audit.record(user, ActorType.USER, actor, event, workspaceName, detail);
    }
    for (UUID user : recipients) {
      if (user.equals(actor)) continue;
      notify(user, message);
    }
  }

  private void notify(UUID userId, String body) {
    try {
      notifications.ifAvailable(bus ->
          bus.raise(NotificationKind.INFO, null, NotificationContext.info(userId, body)));
    } catch (RuntimeException e) {
      log.warn("Could not raise a listing notification", e);
    }
  }
}
