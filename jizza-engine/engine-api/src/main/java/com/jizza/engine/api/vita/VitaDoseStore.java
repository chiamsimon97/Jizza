package com.jizza.engine.api.vita;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Vita's dose log ({@code vita_dose_log}, spec 4.2, 5.2): one row per schedule and due time, made
 * for the next 48 hours. Making a row twice adds nothing. Every per-owner call names the owner and
 * another owner's row reads as missing; the calls without an owner serve the reminder jobs.
 */
public interface VitaDoseStore {

  record DoseRow(UUID id, UUID userId, UUID personId, UUID medicineId, UUID scheduleId, Instant dueAt,
                 String status, Instant snoozedUntil, Instant takenAt, BigDecimal doseTaken,
                 Instant actedAt, UUID actedByUserId, String note, Instant remindedAt,
                 Instant missedAlertedAt, long version) {
    public boolean marked() {
      return "taken".equals(status) || "skipped".equals(status);
    }
  }

  record NewDose(UUID personId, UUID medicineId, UUID scheduleId, Instant dueAt) {}

  /** A mark on a row. {@code status} stays {@code due} for a snooze. */
  record Mark(String status, Instant takenAt, BigDecimal doseTaken, Instant actedAt, UUID actedBy,
              Instant snoozedUntil, String note) {}

  /** Adds the rows not there yet; returns how many were added. */
  int insertMissing(UUID owner, Collection<NewDose> doses);

  Optional<DoseRow> get(UUID owner, UUID id);

  /** The person's live rows with {@code from <= due_at < to}, by time. */
  List<DoseRow> forPerson(UUID owner, UUID personId, Instant from, Instant to);

  /** The person's live rows of one slot. */
  List<DoseRow> slot(UUID owner, UUID personId, Instant dueAt);

  /** The medicine's live rows with {@code from <= due_at < to}, by time. */
  List<DoseRow> forMedicine(UUID owner, UUID medicineId, Instant from, Instant to);

  /**
   * Writes a mark when the row still has {@code version}; the version goes up by one. False when
   * someone else marked it first.
   */
  boolean mark(UUID owner, UUID id, long version, Mark mark);

  /** Removes a schedule's un-acted rows due at or after {@code from}. */
  int removeFutureOfSchedule(UUID owner, UUID scheduleId, Instant from);

  /** Removes a medicine's un-acted rows due at or after {@code from}. */
  int removeFutureOfMedicine(UUID owner, UUID medicineId, Instant from);

  /** Removes a person's un-acted rows due at or after {@code from} (a record closes). */
  int removeFutureOfPerson(UUID owner, UUID personId, Instant from);

  /** Owners with an open schedule, for the jobs. */
  List<UUID> ownersWithSchedules();

  /**
   * Rows of every owner whose reminder is due: still {@code due}, never reminded, and due (or
   * snoozed until) at or before {@code now} and after {@code since}.
   */
  List<DoseRow> toRemind(Instant now, Instant since);

  /** Marks the rows reminded; returns the ids this call marked (another run may have taken some). */
  List<UUID> markReminded(Collection<UUID> ids, Instant at);

  /** Rows of every owner still {@code due} with {@code since < due_at <= before}. */
  List<DoseRow> overdue(Instant before, Instant since);

  /** Marks the rows missed when still {@code due}; returns the ids this call marked. */
  List<UUID> markMissed(Collection<UUID> ids);

  /** Notes the missed alert as sent; returns the ids this call noted. */
  List<UUID> markMissedAlerted(Collection<UUID> ids, Instant at);

  /** The owner's latest due time of any live row; empty when there is none. */
  Optional<Instant> latestDue(UUID owner);

  /** An other reminder ({@code vita_reminder}) that is due to go out. */
  record DueReminder(UUID id, UUID userId, UUID personId) {}

  /** Other reminders of every owner still scheduled with {@code due_at <= now}. */
  List<DueReminder> remindersDue(Instant now);
}
