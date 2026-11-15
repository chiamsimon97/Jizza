package com.jizza.engine.api.event;

import java.util.UUID;

/**
 * The person answered a card raised because a device needs them mid-command (a CAPTCHA, a code,
 * an Ask step, a hand-over): go on, with what they typed if anything, or stop. The answer lives
 * only in this event on its way to the device; it is never saved, logged or audited.
 *
 * @param stop the person chose Stop: the command is cancelled
 * @param answer what they typed (a CAPTCHA's text, a code), or null for "Done"
 */
public record DeviceHelpAnswered(UUID userId, UUID notificationId, UUID deviceId, UUID commandId,
                                 boolean stop, String answer) {

  /** Never prints the answer: an event can end up in a log. */
  @Override
  public String toString() {
    return "DeviceHelpAnswered[notificationId=" + notificationId + ", commandId=" + commandId
        + ", stop=" + stop + ", answered=" + (answer != null) + "]";
  }
}
