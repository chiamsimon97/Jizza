package com.jizza.engine.notifications.config;

import com.jizza.engine.api.enums.NotificationKind;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jizza.notifications")
public class NotificationProperties {

  /** How long a permission request stays answerable before it expires. */
  private Duration permissionExpiry = Duration.ofHours(24);

  /** Reviews are not urgent, so they get longer. */
  private Duration reviewExpiry = Duration.ofDays(7);

  /** A paused run is holding work up, so it is chased sooner. */
  private Duration instructionExpiry = Duration.ofHours(48);

  /** Send a reminder once an item is this far from expiring. */
  private Duration remindWhenWithin = Duration.ofHours(4);

  /** Do not chase the same item more often than this. */
  private Duration reminderCooldown = Duration.ofHours(2);

  /** At most this many reminders before the item is simply left to expire. */
  private int maxReminders = 3;

  /** Send an out-of-app email when an item is chased. */
  private boolean emailEscalation = true;

  private String escalationFrom = "jizza@localhost";

  /** How often the sweeper looks for items to chase or expire. */
  private Duration sweepInterval = Duration.ofMinutes(5);

  /** Poll interval used when an unattended caller waits for an approval. */
  private Duration permissionPollInterval = Duration.ofSeconds(2);

  public Duration expiryFor(NotificationKind kind) {
    return switch (kind) {
      case PERMISSION_REQUEST -> permissionExpiry;
      case WORKFLOW_REVIEW -> reviewExpiry;
      case INSTRUCTION_REQUIRED -> instructionExpiry;
      case INFO -> Duration.ZERO;
    };
  }

  public Duration getPermissionExpiry() { return permissionExpiry; }
  public void setPermissionExpiry(Duration v) { this.permissionExpiry = v; }
  public Duration getReviewExpiry() { return reviewExpiry; }
  public void setReviewExpiry(Duration v) { this.reviewExpiry = v; }
  public Duration getInstructionExpiry() { return instructionExpiry; }
  public void setInstructionExpiry(Duration v) { this.instructionExpiry = v; }
  public Duration getRemindWhenWithin() { return remindWhenWithin; }
  public void setRemindWhenWithin(Duration v) { this.remindWhenWithin = v; }
  public Duration getReminderCooldown() { return reminderCooldown; }
  public void setReminderCooldown(Duration v) { this.reminderCooldown = v; }
  public int getMaxReminders() { return maxReminders; }
  public void setMaxReminders(int v) { this.maxReminders = v; }
  public boolean isEmailEscalation() { return emailEscalation; }
  public void setEmailEscalation(boolean v) { this.emailEscalation = v; }
  public String getEscalationFrom() { return escalationFrom; }
  public void setEscalationFrom(String v) { this.escalationFrom = v; }
  public Duration getSweepInterval() { return sweepInterval; }
  public void setSweepInterval(Duration v) { this.sweepInterval = v; }
  public Duration getPermissionPollInterval() { return permissionPollInterval; }
  public void setPermissionPollInterval(Duration v) { this.permissionPollInterval = v; }
}
