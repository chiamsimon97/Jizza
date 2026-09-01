package com.jizza.platform.usecase.notification;

import com.jizza.engine.api.dto.NotificationDto;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.notification.Answer;
import com.jizza.engine.api.notification.NotificationBus;
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

  public NotificationDto ownedBy(UUID userId, UUID notificationId) {
    NotificationDto notification = bus.get(notificationId);
    if (!notification.userId().equals(userId)) {
      throw new NotFoundException("Notification", notificationId);
    }
    return notification;
  }
}
