package com.jizza.engine.api.scheduler;

import java.time.LocalDate;

/**
 * Whether a recurrence rule (the RRULE subset the scheduler reads: FREQ, INTERVAL, BYDAY,
 * BYMONTHDAY, BYMONTH, COUNT, UNTIL) has an occurrence on a given day, counted from its first day.
 * Lets product code reuse the scheduler's own rule reader without depending on the scheduler.
 */
public interface RecurrenceDays {

  /** True when {@code rrule}, starting on {@code firstDay}, occurs on {@code day}. */
  boolean occursOn(String rrule, LocalDate firstDay, LocalDate day);

  /** Throws a validation error when the rule cannot be read. */
  void validate(String rrule);

  /** Every day: the rule a medicine taken daily uses, and what tests use. */
  RecurrenceDays DAILY = new RecurrenceDays() {
    @Override public boolean occursOn(String rrule, LocalDate firstDay, LocalDate day) { return !day.isBefore(firstDay); }
    @Override public void validate(String rrule) {}
  };
}
