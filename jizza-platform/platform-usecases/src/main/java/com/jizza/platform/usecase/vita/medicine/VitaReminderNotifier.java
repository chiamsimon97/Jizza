package com.jizza.platform.usecase.vita.medicine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.NotificationPriority;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.vita.medicine.VitaReminderRecipients.Recipient;
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
  private final org.springframework.context.ApplicationEventPublisher events;

  public VitaReminderNotifier(NotificationBus bus, AgentDirectory agents, VitaReminderRecipients recipients,
                              org.springframework.context.ApplicationEventPublisher events) {
    this.events = events;
    this.bus = bus;
    this.agents = agents;
    this.recipients = recipients;
  }

  static boolean self(PersonScope p) {
    return "self".equals(p.person().kind());
  }

  /** "Amma · 8:00 medicines", or "Your 8:00 medicines"; with names when allowed. */
  static String slotText(PersonScope p, boolean thePerson, Instant dueAt, ZoneId zone, List<String> names) {
    String time = CLOCK.format(dueAt.atZone(zone));
    String head = thePerson ? "Your " + time + " medicines" : p.person().displayName() + " · " + time + " medicines";
    return names == null || names.isEmpty() ? head : head + ": " + String.join(", ", names);
  }

  static String slotText(PersonScope p, Instant dueAt, ZoneId zone, List<String> names) {
    return slotText(p, self(p), dueAt, zone, names);
  }

  public void slot(PersonScope p, Instant dueAt, ZoneId zone, List<String> names) {
    ObjectNode ctx = Json.obj().put("type", "vita_slot").put("personId", p.personId().toString())
        .put("dueAt", dueAt.toString());
    List<Recipient> to = recipients.forSlot(p);
    send(to, r -> slotText(p, r.thePerson(), dueAt, zone, names), ctx, Duration.ofHours(12), NotificationPriority.URGENT);
    push(to, "vita_slot", p, dueAt);
  }

  public void missed(PersonScope p, Instant dueAt, ZoneId zone) {
    String time = CLOCK.format(dueAt.atZone(zone));
    ObjectNode ctx = Json.obj().put("type", "vita_missed").put("personId", p.personId().toString())
        .put("dueAt", dueAt.toString());
    List<Recipient> to = recipients.forMissed(p);
    send(to, r -> r.thePerson() ? "Your " + time + " medicines were not marked as taken."
        : p.person().displayName() + " · " + time + " medicines were not marked as taken.",
        ctx, Duration.ofDays(1), NotificationPriority.URGENT);
    push(to, "vita_missed", p, dueAt);
  }

  public void dailyCheck(PersonScope p) {
    ObjectNode ctx = Json.obj().put("type", "vita_daily_check").put("personId", p.personId().toString());
    List<Recipient> to = recipients.forSlot(p);
    send(to, r -> r.thePerson() ? "Did you take today's medicines?" : "Did " + p.person().displayName() + " take today's medicines?",
        ctx, Duration.ofHours(12), NotificationPriority.NORMAL);
    push(to, "vita_daily_check", p, null);
  }

  public void refill(PersonScope p, UUID medicineId, String name) {
    String what = name == null ? "a medicine" : name;
    String body = (self(p) ? "You are" : p.person().displayName() + " is") + " running low on " + what + ". Time to refill.";
    ObjectNode ctx = Json.obj().put("type", "vita_refill").put("personId", p.personId().toString())
        .put("medicineId", medicineId.toString());
    send(recipients.forOther(p), r -> body, ctx, Duration.ofDays(7), NotificationPriority.NORMAL);
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
    send(recipients.forOther(p), r -> body, ctx, Duration.ofDays(7), NotificationPriority.NORMAL);
  }

  /** Wakes the recipients' phones with ids only (6d). */
  private void push(List<Recipient> to, String type, PersonScope p, Instant dueAt) {
    for (Recipient r : to) events.publishEvent(new com.jizza.engine.api.event.VitaReminderDue(r.userId(), type, p.personId(), dueAt));
  }

  /** One notification per recipient, from the recipient's own Vita, worded for them. */
  private void send(List<Recipient> to, java.util.function.Function<Recipient, String> body, ObjectNode ctx,
                    Duration expires, NotificationPriority priority) {
    for (Recipient r : to) {
      UUID vita = agents.byKey(r.userId(), VitaToolProvider.AGENT_KEY).map(a -> a.id()).orElse(null);
      bus.raise(NotificationKind.INFO, vita,
          new NotificationContext(r.userId(), body.apply(r), ctx.deepCopy(), null, null, null, expires, priority));
    }
  }
}
