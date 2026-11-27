package com.jizza.platform.usecase.vita.medicine;

import com.jizza.engine.api.scheduler.RecurrenceDays;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns a medicine's schedule into due times (spec 5.2). Times are local times in the person's
 * zone, read with the zone's own rules (never a fixed offset). A local time that does not exist on
 * a day, when the clock jumps forward, moves later by the length of the gap. Pure: no storage, no
 * clock; the caller passes the window.
 */
public final class DoseExpander {

  /** How far ahead dose rows are made (spec 5.2). */
  public static final Duration AHEAD = Duration.ofHours(48);

  private DoseExpander() {}

  /** One schedule as the expander needs it; {@code to} null means open. */
  public record Plan(UUID scheduleId, UUID medicineId, UUID personId, List<LocalTime> times, String recurrence,
                     LocalDate from, LocalDate to) {}

  /** The local time on a day as an instant; a time in a gap moves later by the gap. */
  public static Instant at(LocalDate day, LocalTime time, ZoneId zone) {
    // ZonedDateTime.of moves a time in a gap later by the gap's length and takes the earlier
    // offset in an overlap.
    return ZonedDateTime.of(LocalDateTime.of(day, time), zone).toInstant();
  }

  /** Every due time of the plan with {@code from <= t < to}, in order. */
  public static List<Instant> dueTimes(Plan plan, ZoneId zone, Instant from, Instant to, RecurrenceDays days) {
    List<Instant> out = new ArrayList<>();
    if (!from.isBefore(to)) return out;
    LocalDate first = from.atZone(zone).toLocalDate().minusDays(1);
    LocalDate last = to.atZone(zone).toLocalDate().plusDays(1);
    if (plan.from().isAfter(first)) first = plan.from();
    if (plan.to() != null && plan.to().isBefore(last)) last = plan.to();
    String rule = plan.recurrence() == null || plan.recurrence().isBlank() ? "FREQ=DAILY" : plan.recurrence();
    for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
      if (!days.occursOn(rule, plan.from(), d)) continue;
      for (LocalTime t : plan.times().stream().sorted().toList()) {
        Instant due = at(d, t, zone);
        if (!due.isBefore(from) && due.isBefore(to)) out.add(due);
      }
    }
    return out.stream().distinct().sorted().toList();
  }

  /** A due time shown in another zone, as in Fixture H: "8:00 PM" and "2:30 PM". */
  public static String clock(Instant at, ZoneId zone) {
    return at.atZone(zone).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH));
  }
}
