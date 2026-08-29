package com.jizza.common.notification;

import com.jizza.common.dto.NotificationDto;
import com.jizza.common.enums.NotificationKind;
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
}
