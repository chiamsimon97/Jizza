package com.jizza.platform.usecase.vita.medicine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationPriority;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.tools.VitaToolProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes Vita's reminders into the notifications table (spec 5.2): one per person per time slot,
 * one grouped missed alert, the daily check, refills and other reminders. A notification carries
 * ids only in its detail; the body names the person and the time, and a medicine only when the
 * user turned names on. Nothing here is logged.
 */
@Service
public class VitaReminderNotifier {

  static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH);
  static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

  private final NotificationBus bus;
  private final AgentDirectory agents;
  private final VitaReminderRecipients recipients;

  public VitaReminderNotifier(NotificationBus bus, AgentDirectory agents, VitaReminderRecipients recipients) {
    this.bus = bus;
    this.agents = agents;
    this.recipients = recipients;
  }

  static boolean self(PersonScope p) {
    return "self".equals(p.person().kind());
  }

  /** "Amma · 8:00 medicines", or "Your 8:00 medicines"; with names when allowed. */
  static String slotText(PersonScope p, Instant dueAt, ZoneId zone, List<String> names) {
    String time = CLOCK.format(dueAt.atZone(zone));
    String head = self(p) ? "Your " + time + " medicines" : p.person().displayName() + " · " + time + " medicines";
    return names == null || names.isEmpty() ? head : head + ": " + String.join(", ", names);
  }

  public void slot(PersonScope p, Instant dueAt, ZoneId zone, List<String> names) {
    ObjectNode ctx = Json.obj().put("type", "vita_slot").put("personId", p.personId().toString())
        .put("dueAt", dueAt.toString());
    send(recipients.forSlot(p), p, slotText(p, dueAt, zone, names), ctx, Duration.ofHours(12), NotificationPriority.URGENT);
  }

  public void missed(PersonScope p, Instant dueAt, ZoneId zone) {
    String time = CLOCK.format(dueAt.atZone(zone));
    String body = self(p) ? "Your " + time + " medicines were not marked as taken."
        : p.person().displayName() + " · " + time + " medicines were not marked as taken.";
    ObjectNode ctx = Json.obj().put("type", "vita_missed").put("personId", p.personId().toString())
        .put("dueAt", dueAt.toString());
    send(recipients.forMissed(p), p, body, ctx, Duration.ofDays(1), NotificationPriority.URGENT);
  }

  public void dailyCheck(PersonScope p) {
    String body = self(p) ? "Did you take today's medicines?" : "Did " + p.person().displayName() + " take today's medicines?";
    ObjectNode ctx = Json.obj().put("type", "vita_daily_check").put("personId", p.personId().toString());
    send(recipients.forSlot(p), p, body, ctx, Duration.ofHours(12), NotificationPriority.NORMAL);
  }

  public void refill(PersonScope p, UUID medicineId, String name) {
    String what = name == null ? "a medicine" : name;
    String body = (self(p) ? "You are" : p.person().displayName() + " is") + " running low on " + what + ". Time to refill.";
    ObjectNode ctx = Json.obj().put("type", "vita_refill").put("personId", p.personId().toString())
        .put("medicineId", medicineId.toString());
    send(recipients.forOther(p), p, body, ctx, Duration.ofDays(7), NotificationPriority.NORMAL);
  }

  public void other(PersonScope p, UUID reminderId, String kind, String title, Instant dueAt, ZoneId zone) {
    String who = self(p) ? "You" : p.person().displayName();
    String day = DAY.format(dueAt.atZone(zone));
    String body = title != null && !title.isBlank() ? who + ": " + title
        : switch (kind) {
          case "visit" -> who + ": a doctor's visit is coming up on " + day + ".";
          case "vaccination" -> who + ": a vaccination is due on " + day + ".";
          case "policy_renewal" -> who + ": a health policy renews on " + day + ".";
          case "check_due" -> who + ": a check is due on " + day + ".";
          default -> who + ": a reminder for " + day + ".";
        };
    ObjectNode ctx = Json.obj().put("type", "vita_reminder").put("personId", p.personId().toString())
        .put("reminderId", reminderId.toString()).put("kind", kind);
    send(recipients.forOther(p), p, body, ctx, Duration.ofDays(7), NotificationPriority.NORMAL);
  }

  private void send(List<UUID> to, PersonScope p, String body, ObjectNode ctx, Duration expires, NotificationPriority priority) {
    UUID vita = agents.byKey(p.owner(), VitaToolProvider.AGENT_KEY).map(a -> a.id()).orElse(null);
    for (UUID user : to) {
      bus.raise(NotificationKind.INFO, vita, new NotificationContext(user, body, ctx, null, null, null, expires, priority));
    }
  }
}
