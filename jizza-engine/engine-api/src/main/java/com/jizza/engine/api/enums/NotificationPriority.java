package com.jizza.engine.api.enums;

/**
 * How soon something waiting on the user should reach them. Computed by the engine from what is at
 * stake; an agent can suggest one but never raise it past what its action warrants.
 */
public enum NotificationPriority {
  /** Delivered now, inside the app and by an out-of-app nudge, even during quiet hours. */
  URGENT,
  /** Shown in the app now and chased by the usual reminders. */
  NORMAL,
  /** Waits for the user's next visit. */
  LOW;

  /**
   * How urgent an approval request is. Something that moves money, deletes or messages someone
   * while nobody is watching is chased now; the rest can wait for the app.
   */
  public static NotificationPriority forApproval(ActionType type, boolean unattended) {
    if (type == null) return NORMAL;
    boolean weighty = type == ActionType.MONEY || type == ActionType.DELETE
        || type == ActionType.SEND;
    return weighty && unattended ? URGENT : NORMAL;
  }

  /** The stricter of what was asked for and what is allowed: an agent can lower, never raise. */
  public static NotificationPriority capped(NotificationPriority asked, NotificationPriority cap) {
    if (asked == null) return cap;
    if (cap == null) return asked;
    return asked.ordinal() >= cap.ordinal() ? asked : cap;
  }
}
