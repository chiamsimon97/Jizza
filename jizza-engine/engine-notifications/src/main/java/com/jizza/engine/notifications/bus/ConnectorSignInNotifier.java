package com.jizza.engine.notifications.bus;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.activity.Activity;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.connector.ConnectorSignIn;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.util.Json;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tells the person a connector needs signing in again: a card on the chat reply that ran into it
 * (through the job's activity), and one inbox item a day at most, so a connector that keeps failing
 * in scheduled runs does not fill the inbox. The inbox item is written in its own transaction:
 * the call that found the problem usually fails and rolls back its own.
 */
@Component
public class ConnectorSignInNotifier implements ConnectorSignIn {

  private static final Logger log = LoggerFactory.getLogger(ConnectorSignInNotifier.class);

  /** One inbox item per connector in this long. */
  static final Duration QUIET = Duration.ofHours(24);

  private final ObjectProvider<NotificationBus> bus;
  private final TransactionTemplate separate;

  public ConnectorSignInNotifier(ObjectProvider<NotificationBus> bus, PlatformTransactionManager transactions) {
    this.bus = bus;
    this.separate = new TransactionTemplate(transactions);
    this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void lost(UUID userId, String kind, UUID id, String name) {
    if (userId == null || id == null) return;
    String label = name == null || name.isBlank() ? "A connector" : name;
    String path = ConnectorSignIn.path(kind, id);
    if (Activity.active()) {
      Activity.emit(ActivityTypes.CONNECTOR_SIGN_IN, null, null, p -> p
          .put("kind", kind).put("id", id.toString()).put("name", label).put("path", path));
    }
    NotificationBus notifications = bus.getIfAvailable();
    if (notifications == null) return;
    String body = label + " needs you to sign in again. Until then, your agents and workflows can't use it.";
    try {
      separate.executeWithoutResult(status -> {
        if (notifications.raisedSince(userId, null, NotificationKind.INFO, body, Instant.now().minus(QUIET))) {
          return;
        }
        ObjectNode detail = Json.obj().put("source", "connector").put("kind", "sign_in")
            .put("connector", kind).put("connectorId", id.toString()).put("name", label).put("link", path);
        notifications.raise(NotificationKind.INFO, null,
            new NotificationContext(userId, body, detail, null, null, null, null));
      });
    } catch (RuntimeException e) {
      log.warn("Could not tell user {} that connector {} needs signing in again", userId, id, e);
    }
  }
}
