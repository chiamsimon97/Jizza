package com.jizza.engine.scheduler.recurrence;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.scheduler.RecurrenceDays;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

/** {@link RecurrenceDays} on the scheduler's own {@link Rrule}, read in local dates only. */
@Component
public class RruleRecurrenceDays implements RecurrenceDays {

  @Override
  public boolean occursOn(String rrule, LocalDate firstDay, LocalDate day) {
    if (day.isBefore(firstDay)) return false;
    Rrule rule = Rrule.parse(rrule);
    ZonedDateTime anchor = firstDay.atStartOfDay(ZoneOffset.UTC);
    ZonedDateTime before = day.atStartOfDay(ZoneOffset.UTC).minusNanos(1);
    // Days only: the rule's time of day is the anchor's midnight, so the next occurrence after the
    // last instant of the day before falls on {@code day} exactly when the rule matches it.
    int already = 0;
    return rule.next(anchor, before, already).map(z -> z.toLocalDate().equals(day)).orElse(false);
  }

  @Override
  public void validate(String rrule) {
    if (rrule == null || !Rrule.looksLikeRrule(rrule)) {
      throw new ValidationException("recurrence must be an RRULE such as FREQ=DAILY or FREQ=WEEKLY;BYDAY=MO,TH.");
    }
    Rrule.parse(rrule);
  }
}
