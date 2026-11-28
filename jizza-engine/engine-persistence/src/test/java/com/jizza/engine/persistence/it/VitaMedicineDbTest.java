package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Vita's Phase 1a part 2 tables (V68): medicines, their schedules and events, the dose log (one
 * row per schedule and due time) and other reminders, with their checks, cascades and the
 * reminder settings.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaMedicineDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user() {
    return (UUID) em.createNativeQuery("insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", "med-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user) {
    return (UUID) em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Someone', 'parent') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private UUID medicine(UUID u, UUID p) {
    return (UUID) em.createNativeQuery("insert into vita_medicine (user_id, person_id, name) values (:u, :p, 'metformin 500') returning id")
        .setParameter("u", u).setParameter("p", p).getSingleResult();
  }

  private UUID schedule(UUID u, UUID p, UUID m) {
    return (UUID) em.createNativeQuery("""
        insert into vita_medicine_schedule (user_id, person_id, medicine_id, times, effective_from)
        values (:u, :p, :m, array['08:00','20:00'], date '2026-12-01') returning id""")
        .setParameter("u", u).setParameter("p", p).setParameter("m", m).getSingleResult();
  }

  private int count(String sql, Object... params) {
    var q = em.createNativeQuery(sql);
    for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
    return ((Number) q.getSingleResult()).intValue();
  }

  @Test
  void aMedicineKeepsItsNameAsWrittenAndChecksItsValues() {
    UUID u = user();
    UUID p = person(u);
    UUID m = medicine(u, p);
    assertThat(em.createNativeQuery("select name || '|' || form || '|' || system || '|' || status from vita_medicine where id = :m")
        .setParameter("m", m).getSingleResult()).isEqualTo("metformin 500|tablet|allopathy|active");
    assertThatThrownBy(() -> em.createNativeQuery("insert into vita_medicine (user_id, person_id, name, form) values (:u, :p, 'x', 'pill')")
        .setParameter("u", u).setParameter("p", p).executeUpdate()).hasMessageContaining("vita_medicine_form_check");
  }

  @Test
  void stockCannotGoBelowZero() {
    UUID u = user();
    UUID p = person(u);
    assertThatThrownBy(() -> em.createNativeQuery("insert into vita_medicine (user_id, person_id, name, stock_units) values (:u, :p, 'x', -1)")
        .setParameter("u", u).setParameter("p", p).executeUpdate()).hasMessageContaining("vita_medicine_stock_check");
  }

  @Test
  void anEndDateIsNotBeforeTheStart() {
    UUID u = user();
    UUID p = person(u);
    assertThatThrownBy(() -> em.createNativeQuery("""
        insert into vita_medicine (user_id, person_id, name, start_date, end_date)
        values (:u, :p, 'x', date '2026-12-10', date '2026-12-09')""")
        .setParameter("u", u).setParameter("p", p).executeUpdate()).hasMessageContaining("vita_medicine_dates_check");
  }

  @Test
  void aScheduleNeedsOneToTwelveTimesAndAClosedOneMayEndTheDayBeforeItStarted() {
    UUID u = user();
    UUID p = person(u);
    UUID m = medicine(u, p);
    UUID s = schedule(u, p, m);
    em.createNativeQuery("update vita_medicine_schedule set effective_to = date '2026-11-30' where id = :s")
        .setParameter("s", s).executeUpdate();
    assertThatThrownBy(() -> em.createNativeQuery("""
        insert into vita_medicine_schedule (user_id, person_id, medicine_id, times, effective_from)
        values (:u, :p, :m, array[]::text[], date '2026-12-01')""")
        .setParameter("u", u).setParameter("p", p).setParameter("m", m).executeUpdate())
        .hasMessageContaining("vita_medicine_schedule_has_time");
  }

  @Test
  void oneDoseRowPerScheduleAndTimeAndRowsGoWithTheMedicine() {
    UUID u = user();
    UUID p = person(u);
    UUID m = medicine(u, p);
    UUID s = schedule(u, p, m);
    String insert = """
        insert into vita_dose_log (user_id, person_id, medicine_id, schedule_id, due_at)
        values (:u, :p, :m, :s, timestamptz '2026-12-01 02:30:00+00') on conflict (schedule_id, due_at) do nothing""";
    for (int i = 0; i < 2; i++) {
      em.createNativeQuery(insert).setParameter("u", u).setParameter("p", p).setParameter("m", m).setParameter("s", s).executeUpdate();
    }
    assertThat(count("select count(*) from vita_dose_log where schedule_id = ?1", s)).isEqualTo(1);
    assertThat(count("select count(*) from vita_dose_log where schedule_id = ?1 and status = 'due' and version = 0", s)).isEqualTo(1);
    em.createNativeQuery("delete from vita_medicine where id = :m").setParameter("m", m).executeUpdate();
    assertThat(count("select count(*) from vita_dose_log where schedule_id = ?1", s)).isZero();
    assertThat(count("select count(*) from vita_medicine_schedule where id = ?1", s)).isZero();
    UUID m2 = medicine(u, p);
    UUID s2 = schedule(u, p, m2);
    assertThatThrownBy(() -> em.createNativeQuery("""
        insert into vita_dose_log (user_id, person_id, medicine_id, schedule_id, due_at, status)
        values (:u, :p, :m, :s, now(), 'late')""").setParameter("u", u).setParameter("p", p).setParameter("m", m2)
        .setParameter("s", s2).executeUpdate()).hasMessageContaining("vita_dose_log_status_check");
  }

  @Test
  void anEventNamesAKnownKind() {
    UUID u = user();
    UUID p = person(u);
    UUID m = medicine(u, p);
    em.createNativeQuery("""
        insert into vita_medicine_event (user_id, person_id, medicine_id, kind, on_date, from_text, to_text)
        values (:u, :p, :m, 'dose_changed', date '2026-12-16', '40 mg', '80 mg')""")
        .setParameter("u", u).setParameter("p", p).setParameter("m", m).executeUpdate();
    em.createNativeQuery("delete from fin_person where id = :p").setParameter("p", p).executeUpdate();
    assertThat(count("select count(*) from vita_medicine_event where medicine_id = ?1", m)).isZero();
    UUID p2 = person(u);
    UUID m2 = medicine(u, p2);
    assertThatThrownBy(() -> em.createNativeQuery("""
        insert into vita_medicine_event (user_id, person_id, medicine_id, kind, on_date)
        values (:u, :p, :m, 'swapped', current_date)""")
        .setParameter("u", u).setParameter("p", p2).setParameter("m", m2).executeUpdate())
        .hasMessageContaining("vita_medicine_event_kind_check");
  }

  @Test
  void aReminderHasAKnownKindAndStatusAndTheSettingsAreSeeded() {
    UUID u = user();
    UUID p = person(u);
    em.createNativeQuery("insert into vita_reminder (user_id, person_id, kind, due_at) values (:u, :p, 'visit', now())")
        .setParameter("u", u).setParameter("p", p).executeUpdate();
    assertThat(count("select count(*) from vita_config where user_id is null and key like 'reminder.%'")).isEqualTo(5);
    assertThat(em.createNativeQuery("select value->>'minutes' from vita_config where key = 'reminder.missed_after_minutes' and user_id is null")
        .getSingleResult()).isEqualTo("60");
    assertThatThrownBy(() -> em.createNativeQuery("insert into vita_reminder (user_id, person_id, kind, due_at) values (:u, :p, 'birthday', now())")
        .setParameter("u", u).setParameter("p", p).executeUpdate()).hasMessageContaining("vita_reminder_kind_check");
  }
}
