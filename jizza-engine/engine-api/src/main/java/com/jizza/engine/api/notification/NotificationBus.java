package com.jizza.engine.api.notification;

import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.enums.NotificationKind;
import java.util.List;
import java.util.UUID;

/**
 * Every agent-to-user message goes through here and is relayed by Jizza, attributed by agent name
 * (R37). Blocking items carry an expiry and escalate rather than hanging unseen (R39).
 */
public interface NotificationBus {

  NotificationDto raise(NotificationKind kind, UUID sourceAgentId, NotificationContext ctx);

  NotificationDto answer(UUID notificationId, Answer a);

  List<NotificationDto> list(UUID userId, boolean unreadOnly);

  NotificationDto get(UUID notificationId);

  void markRead(UUID notificationId);

  /**
   * Block until a permission request is answered, or the wait budget elapses.
   *
   * @return true when approved
   */
  boolean awaitPermission(UUID notificationId, java.time.Duration timeout);

  /** Whether the same item from the same agent was already raised since then, to avoid repeats. */
  default boolean raisedSince(UUID userId, UUID sourceAgentId, NotificationKind kind, String body,
                              java.time.Instant since) {
    return list(userId, false).stream()
        .anyMatch(n -> n.kind() == kind && java.util.Objects.equals(n.sourceAgentId(), sourceAgentId)
            && java.util.Objects.equals(n.body(), body)
            && n.createdAt() != null && n.createdAt().isAfter(since));
  }
}
