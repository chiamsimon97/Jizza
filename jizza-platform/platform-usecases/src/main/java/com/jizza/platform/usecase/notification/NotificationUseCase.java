package com.jizza.platform.usecase.notification;

import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.enums.NotificationPriority;
import com.jizza.engine.api.enums.NotificationStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.notification.Answer;
import com.jizza.engine.api.notification.NotificationBus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The user's side of a notification: read the inbox, mark an item read, answer one (R37/R38).
 *
 * <p>There is little product policy here to speak of -- raising, delivering, escalating and
 * answering a notification are all mechanism that reaches into the workflow engine and procedure
 * review, so they stay behind {@link NotificationBus} itself, unlike the domains split out so far.
 * What this class does own is the one thing that was still sitting in the controller: a
 * notification belonging to somebody else is reported as missing rather than forbidden, same as
 * every other owned resource in the platform.
 */
@Service
public class NotificationUseCase {

  private final NotificationBus bus;

  public NotificationUseCase(NotificationBus bus) {
    this.bus = bus;
  }

  public List<NotificationDto> list(UUID userId, boolean unreadOnly) {
    return bus.list(userId, unreadOnly);
  }

  public NotificationDto get(UUID userId, UUID notificationId) {
    return ownedBy(userId, notificationId);
  }

  public void markRead(UUID userId, UUID notificationId) {
    ownedBy(userId, notificationId);
    bus.markRead(notificationId);
  }

  public NotificationDto answer(UUID userId, UUID notificationId, Answer answer) {
    ownedBy(userId, notificationId);
    return bus.answer(notificationId, answer);
  }

  public void markUnread(UUID userId, UUID notificationId) {
    ownedBy(userId, notificationId);
    bus.markUnread(notificationId);
  }

  public int markAllRead(UUID userId) {
    return bus.markAllRead(userId);
  }

  public NotificationDto archive(UUID userId, UUID notificationId, boolean archived) {
    ownedBy(userId, notificationId);
    return bus.archive(notificationId, archived);
  }

  public NotificationDto snooze(UUID userId, UUID notificationId, Instant until) {
    ownedBy(userId, notificationId);
    return bus.snooze(notificationId, until);
  }

  /**
   * The counts behind the sidebar badge and the inbox header.
   *
   * @param needsYou items waiting on an answer, not snoozed or filed away
   * @param urgent   the urgent ones among them
   * @param unread   everything unread still in the inbox, updates included
   */
  public record Summary(int needsYou, int urgent, int unread) {}

  public Summary summary(UUID userId) {
    Instant now = Instant.now();
    List<NotificationDto> inbox = bus.list(userId, false).stream()
        .filter(n -> n.archivedAt() == null)
        .filter(n -> n.snoozedUntil() == null || !n.snoozedUntil().isAfter(now))
        .toList();
    List<NotificationDto> waiting = inbox.stream().filter(NotificationDto::open).toList();
    int urgent = (int) waiting.stream()
        .filter(n -> n.priority() == NotificationPriority.URGENT).count();
    int unread = (int) inbox.stream().filter(n -> n.status() == NotificationStatus.UNREAD).count();
    return new Summary(waiting.size(), urgent, unread);
  }

  public NotificationDto ownedBy(UUID userId, UUID notificationId) {
    NotificationDto notification = bus.get(notificationId);
    if (!notification.userId().equals(userId)) {
      throw new NotFoundException("Notification", notificationId);
    }
    return notification;
  }
}
