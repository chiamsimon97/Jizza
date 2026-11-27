package com.jizza.engine.infra.vita;

import com.jizza.engine.api.vita.VitaDoseStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link VitaDoseStore} over {@code vita_dose_log}. */
@Component
public class JdbcVitaDoseStore implements VitaDoseStore {

  private static final String COLS = """
      id, user_id, person_id, medicine_id, schedule_id, due_at, status, snoozed_until, taken_at,
      dose_taken, acted_at, acted_by_user_id, note, reminded_at, missed_alerted_at, version""";

  private final JdbcTemplate jdbc;

  public JdbcVitaDoseStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public int insertMissing(UUID owner, Collection<NewDose> doses) {
    int added = 0;
    for (NewDose d : doses) {
      // The schedule must be the owner's and the person's: anyone else's adds nothing.
      added += jdbc.update("""
          insert into vita_dose_log (user_id, person_id, medicine_id, schedule_id, due_at)
          select s.user_id, s.person_id, s.medicine_id, s.id, ?
            from vita_medicine_schedule s
           where s.id = ? and s.user_id = ? and s.person_id = ? and s.medicine_id = ?
          on conflict (schedule_id, due_at) do nothing""",
          ts(d.dueAt()), d.scheduleId(), owner, d.personId(), d.medicineId());
    }
    return added;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<DoseRow> get(UUID owner, UUID id) {
    return jdbc.query("select " + COLS + " from vita_dose_log where id = ? and user_id = ? and deleted_at is null",
        JdbcVitaDoseStore::row, id, owner).stream().findFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public List<DoseRow> forPerson(UUID owner, UUID personId, Instant from, Instant to) {
    return jdbc.query("select " + COLS + """
         from vita_dose_log
        where user_id = ? and person_id = ? and due_at >= ? and due_at < ? and deleted_at is null
        order by due_at, medicine_id""", JdbcVitaDoseStore::row, owner, personId, ts(from), ts(to));
  }

  @Override
  @Transactional(readOnly = true)
  public List<DoseRow> slot(UUID owner, UUID personId, Instant dueAt) {
    return jdbc.query("select " + COLS + """
         from vita_dose_log
        where user_id = ? and person_id = ? and due_at = ? and deleted_at is null
        order by medicine_id""", JdbcVitaDoseStore::row, owner, personId, ts(dueAt));
  }

  @Override
  @Transactional(readOnly = true)
  public List<DoseRow> forMedicine(UUID owner, UUID medicineId, Instant from, Instant to) {
    return jdbc.query("select " + COLS + """
         from vita_dose_log
        where user_id = ? and medicine_id = ? and due_at >= ? and due_at < ? and deleted_at is null
        order by due_at""", JdbcVitaDoseStore::row, owner, medicineId, ts(from), ts(to));
  }

  @Override
  @Transactional
  public boolean mark(UUID owner, UUID id, long version, Mark m) {
    return jdbc.update("""
        update vita_dose_log
           set status = ?, taken_at = ?, dose_taken = ?, acted_at = ?, acted_by_user_id = ?,
               snoozed_until = ?, note = coalesce(?, note),
               reminded_at = case when ? then null else reminded_at end,
               version = version + 1, updated_at = now()
         where id = ? and user_id = ? and version = ? and deleted_at is null""",
        m.status(), ts(m.takenAt()), m.doseTaken(), ts(m.actedAt()), m.actedBy(), ts(m.snoozedUntil()),
        m.note(), m.snoozedUntil() != null, id, owner, version) > 0;
  }

  @Override
  @Transactional
  public int removeFutureOfSchedule(UUID owner, UUID scheduleId, Instant from) {
    return jdbc.update("""
        delete from vita_dose_log
         where user_id = ? and schedule_id = ? and due_at >= ? and status = 'due' and acted_at is null""",
        owner, scheduleId, ts(from));
  }

  @Override
  @Transactional
  public int removeFutureOfMedicine(UUID owner, UUID medicineId, Instant from) {
    return jdbc.update("""
        delete from vita_dose_log
         where user_id = ? and medicine_id = ? and due_at >= ? and status = 'due' and acted_at is null""",
        owner, medicineId, ts(from));
  }

  @Override
  @Transactional
  public int removeFutureOfPerson(UUID owner, UUID personId, Instant from) {
    return jdbc.update("""
        delete from vita_dose_log
         where user_id = ? and person_id = ? and due_at >= ? and status = 'due' and acted_at is null""",
        owner, personId, ts(from));
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> ownersWithSchedules() {
    return jdbc.queryForList("""
        select distinct s.user_id
          from vita_medicine_schedule s join vita_medicine m on m.id = s.medicine_id
         where s.deleted_at is null and m.deleted_at is null and m.status = 'active'
           and (s.effective_to is null or s.effective_to >= current_date - 1)
         order by s.user_id""", UUID.class);
  }

  @Override
  @Transactional(readOnly = true)
  public List<DoseRow> toRemind(Instant now, Instant since) {
    return jdbc.query("select " + COLS + """
         from vita_dose_log
        where status = 'due' and deleted_at is null and reminded_at is null
          and coalesce(snoozed_until, due_at) <= ? and due_at > ?
        order by user_id, person_id, due_at""", JdbcVitaDoseStore::row, ts(now), ts(since));
  }

  @Override
  @Transactional
  public List<UUID> markReminded(Collection<UUID> ids, Instant at) {
    if (ids.isEmpty()) return List.of();
    return jdbc.queryForList("""
        update vita_dose_log set reminded_at = ?, updated_at = now()
         where id = any(cast(? as uuid[])) and reminded_at is null and status = 'due'
        returning id""", UUID.class, ts(at), array(ids));
  }

  @Override
  @Transactional(readOnly = true)
  public List<DoseRow> overdue(Instant before, Instant since) {
    return jdbc.query("select " + COLS + """
         from vita_dose_log
        where status = 'due' and deleted_at is null and due_at <= ? and due_at > ?
        order by user_id, person_id, due_at""", JdbcVitaDoseStore::row, ts(before), ts(since));
  }

  @Override
  @Transactional
  public List<UUID> markMissed(Collection<UUID> ids) {
    if (ids.isEmpty()) return List.of();
    return jdbc.queryForList("""
        update vita_dose_log set status = 'missed', snoozed_until = null, version = version + 1,
               updated_at = now()
         where id = any(cast(? as uuid[])) and status = 'due'
        returning id""", UUID.class, array(ids));
  }

  @Override
  @Transactional
  public List<UUID> markMissedAlerted(Collection<UUID> ids, Instant at) {
    if (ids.isEmpty()) return List.of();
    return jdbc.queryForList("""
        update vita_dose_log set missed_alerted_at = ?, updated_at = now()
         where id = any(cast(? as uuid[])) and missed_alerted_at is null
        returning id""", UUID.class, ts(at), array(ids));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Instant> latestDue(UUID owner) {
    Timestamp t = jdbc.queryForObject("select max(due_at) from vita_dose_log where user_id = ? and deleted_at is null",
        Timestamp.class, owner);
    return Optional.ofNullable(t).map(Timestamp::toInstant);
  }

  @Override
  @Transactional(readOnly = true)
  public List<DueReminder> remindersDue(Instant now) {
    return jdbc.query("""
        select id, user_id, person_id from vita_reminder
         where status = 'scheduled' and deleted_at is null and due_at <= ?
         order by due_at limit 1000""",
        (rs, n) -> new DueReminder(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)),
        ts(now));
  }

  private static String array(Collection<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant at(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static DoseRow row(ResultSet rs, int n) throws SQLException {
    return new DoseRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
        rs.getObject("person_id", UUID.class), rs.getObject("medicine_id", UUID.class),
        rs.getObject("schedule_id", UUID.class), at(rs, "due_at"), rs.getString("status"),
        at(rs, "snoozed_until"), at(rs, "taken_at"), rs.getBigDecimal("dose_taken"), at(rs, "acted_at"),
        rs.getObject("acted_by_user_id", UUID.class), rs.getString("note"), at(rs, "reminded_at"),
        at(rs, "missed_alerted_at"), rs.getLong("version"));
  }
}
