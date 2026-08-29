package com.jizza.engine.scheduler.recurrence;

import com.jizza.engine.api.enums.CatchUpPolicy;
import com.jizza.engine.api.error.ValidationException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Computes the next run for a recurring task, in the task's own zone (R30).
 *
 * <p>Accepts both a Spring cron expression and the RRULE subset. Both are evaluated as local
 * wall-clock time in the configured zone, which is what makes them DST-safe: a 09:00 job stays at
 * 09:00 across a transition instead of moving with UTC.
 */
@Component
public class RecurrenceCalculator {

  public ZoneId zoneOf(String timezone) {
    try {
      return timezone == null || timezone.isBlank() ? ZoneId.of("Asia/Kolkata") : ZoneId.of(timezone);
    } catch (RuntimeException e) {
      throw new ValidationException("Unknown timezone '" + timezone + "'");
    }
  }

  /** Validates an expression at save time, so a bad rule fails when the user types it. */
  public void validate(String expression, String timezone) {
    ZoneId zone = zoneOf(timezone);
    ZonedDateTime now = ZonedDateTime.now(zone);
    if (next(expression, timezone, now, now, 0).isEmpty()) {
      throw new ValidationException(
          "That schedule has no future occurrences: " + expression);
    }
  }

  public Optional<Instant> nextAfter(String expression, String timezone, Instant anchor,
                                     Instant after, int alreadyRun) {
    ZoneId zone = zoneOf(timezone);
    return next(expression, timezone, anchor.atZone(zone), after.atZone(zone), alreadyRun)
        .map(ChronoConversions::toInstant);
  }

  private Optional<ZonedDateTime> next(String expression, String timezone, ZonedDateTime anchor,
                                       ZonedDateTime after, int alreadyRun) {
    if (expression == null || expression.isBlank()) {
      throw new ValidationException("A recurring task needs a cron expression or an RRULE");
    }
    if (Rrule.looksLikeRrule(expression)) {
      return Rrule.parse(expression).next(anchor, after, alreadyRun);
    }
    CronExpression cron;
    try {
      cron = CronExpression.parse(expression.trim());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Not a valid cron expression or RRULE: " + expression);
    }
    return Optional.ofNullable(cron.next(after.withZoneSameInstant(zoneOf(timezone))));
  }

  /**
   * Reconciles a schedule that was missed while the process was down (R30).
   *
   * @return the next run to set, or empty when the schedule is exhausted
   */
  public Optional<Instant> reconcileMissed(String expression, String timezone, Instant anchor,
                                           Instant missedDue, Instant now, CatchUpPolicy policy,
                                           int alreadyRun) {
    return switch (policy) {
      // Fire once now to acknowledge the missed window, then resume the normal cadence.
      case RUN_ONCE -> Optional.of(now);
      // Skip everything that was missed and pick up from the next future occurrence.
      case SKIP -> nextAfter(expression, timezone, anchor, now, alreadyRun);
      // Work through the backlog one occurrence at a time.
      case RUN_ALL -> Optional.of(missedDue);
    };
  }

  static final class ChronoConversions {
    private ChronoConversions() {}

    static Instant toInstant(ZonedDateTime value) { return value.toInstant(); }
  }
}
