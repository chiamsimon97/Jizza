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

  /** Puts a read item back to unread, to come back to it later. Answered items stay answered. */
  default void markUnread(UUID notificationId) {
    throw new UnsupportedOperationException("Not supported by this notification bus");
  }

  /** Marks every unread item of the user's read, returning how many changed. */
  default int markAllRead(UUID userId) {
    throw new UnsupportedOperationException("Not supported by this notification bus");
  }

  /**
   * Files an item away, or brings it back. Something still waiting on an answer cannot be filed:
   * the work behind it would sit paused until it expired.
   */
  default NotificationDto archive(UUID notificationId, boolean archived) {
    throw new UnsupportedOperationException("Not supported by this notification bus");
  }

  /** Hides an item from the inbox until {@code until}; null brings it back now. */
  default NotificationDto snooze(UUID notificationId, java.time.Instant until) {
    throw new UnsupportedOperationException("Not supported by this notification bus");
  }

  /**
   * Closes the run's items that are still waiting on an answer, because nothing is waiting for
   * that answer any more: the run ended, or the step was answered another way (from the run view).
   * With {@code nodeId}, only the items about that step. {@code reason} is shown on each item.
   *
   * @return how many items were closed
   */
  default int closeForRun(java.util.UUID runId, String nodeId, String reason) {
    return 0;
  }

  /**
   * Closes one item that is still waiting on an answer, because nothing waits for it any more (a
   * device's question whose command ended). {@code reason} is shown on the item.
   *
   * @return whether it was open and is now closed
   */
  default boolean closeOpen(java.util.UUID notificationId, String reason) {
    return false;
  }

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
