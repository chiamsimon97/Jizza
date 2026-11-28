package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The journal and goals (V75): one live journal entry per person per day; a medical goal keeps no
 * figure of its own; one check-in per goal and week, on a Monday; everything goes with its person
 * and a check-in with its goal.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaJournalGoalsDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user() {
    return (UUID) em.createNativeQuery("insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", "vjg-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user) {
    return (UUID) em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Me', 'self') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private void entry(UUID u, UUID p, String day, Integer mood, Integer pain) {
    em.createNativeQuery("insert into vita_journal_entry (user_id, person_id, entry_date, mood, pain, symptoms) "
            + "values (:u, :p, cast(:d as date), :m, :pain, array['headache'])")
        .setParameter("u", u).setParameter("p", p).setParameter("d", day).setParameter("m", mood)
        .setParameter("pain", pain).executeUpdate();
  }

  private UUID goal(UUID u, UUID p, String kind, Integer target) {
    return (UUID) em.createNativeQuery("insert into vita_goal (user_id, person_id, kind, target) values (:u, :p, :k, :t) returning id")
        .setParameter("u", u).setParameter("p", p).setParameter("k", kind).setParameter("t", target).getSingleResult();
  }

  private void checkin(UUID u, UUID p, UUID g, String week) {
    em.createNativeQuery("insert into vita_goal_checkin (user_id, person_id, goal_id, week_start, value) "
            + "values (:u, :p, :g, cast(:w as date), 70)")
        .setParameter("u", u).setParameter("p", p).setParameter("g", g).setParameter("w", week).executeUpdate();
  }

  private int count(String sql, Object... params) {
    var q = em.createNativeQuery(sql);
    for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
    return ((Number) q.getSingleResult()).intValue();
  }

  private String refused(Runnable statement) {
    em.createNativeQuery("savepoint refused").executeUpdate();
    try {
      statement.run();
    } catch (RuntimeException e) {
      em.createNativeQuery("rollback to savepoint refused").executeUpdate();
      Throwable t = e;
      StringBuilder all = new StringBuilder();
      while (t != null) {
        all.append(t.getMessage()).append(' ');
        t = t.getCause();
      }
      return all.toString();
    }
    throw new AssertionError("the statement was accepted");
  }

  @Test
  void oneLiveJournalEntryADayWithScalesChecked() {
    UUID u = user();
    UUID p = person(u);
    entry(u, p, "2026-11-20", 4, 2);
    assertThat(refused(() -> entry(u, p, "2026-11-20", 3, 1))).contains("vita_journal_one_per_day");
    em.createNativeQuery("update vita_journal_entry set deleted_at = now() where person_id = :p").setParameter("p", p).executeUpdate();
    entry(u, p, "2026-11-20", 3, 1);
    assertThat(refused(() -> entry(u, p, "2026-11-21", 6, 1))).contains("vita_journal_mood_check");
    assertThat(refused(() -> entry(u, p, "2026-11-22", 3, 11))).contains("vita_journal_pain_check");
    assertThat(count("select count(*) from vita_journal_entry where person_id = ?1", p)).isEqualTo(2);
  }

  @Test
  void aMedicalGoalKeepsNoFigureOfItsOwn() {
    UUID u = user();
    UUID p = person(u);
    assertThat(goal(u, p, "bp_range", null)).isNotNull();
    assertThat(goal(u, p, "weight", 70)).isNotNull();
    assertThat(refused(() -> goal(u, p, "hba1c", 7))).contains("vita_goal_medical_no_target");
    assertThat(refused(() -> goal(u, p, "glucose_range", 130))).contains("vita_goal_medical_no_target");
    assertThat(refused(() -> goal(u, p, "cycle", null))).contains("vita_goal_kind_check");
    assertThat(refused(() -> em.createNativeQuery("insert into vita_goal (user_id, person_id, kind, status) values (:u, :p, 'steps', 'done')")
        .setParameter("u", u).setParameter("p", p).executeUpdate())).contains("vita_goal_status_check");
  }

  @Test
  void oneCheckInPerGoalAndWeekOnAMondayAndEverythingGoesWithItsPerson() {
    UUID u = user();
    UUID p = person(u);
    UUID g = goal(u, p, "weight", 70);
    checkin(u, p, g, "2026-11-23");
    assertThat(refused(() -> checkin(u, p, g, "2026-11-23"))).contains("vita_goal_checkin_one");
    assertThat(refused(() -> checkin(u, p, g, "2026-11-24"))).contains("vita_goal_checkin_monday");
    entry(u, p, "2026-11-20", 4, 0);
    em.createNativeQuery("delete from vita_goal where id = :g").setParameter("g", g).executeUpdate();
    assertThat(count("select count(*) from vita_goal_checkin where goal_id = ?1", g)).isZero();
    UUID g2 = goal(u, p, "steps", 6000);
    checkin(u, p, g2, "2026-11-23");
    em.createNativeQuery("delete from fin_person where id = :p").setParameter("p", p).executeUpdate();
    assertThat(count("select count(*) from vita_goal where person_id = ?1", p)).isZero();
    assertThat(count("select count(*) from vita_goal_checkin where person_id = ?1", p)).isZero();
    assertThat(count("select count(*) from vita_journal_entry where person_id = ?1", p)).isZero();
  }

  @Test
  void theWeeksToMeetAGoalAreAProductSetting() {
    assertThat(count("select count(*) from vita_config where key = 'goals.met_after_weeks' and user_id is null "
        + "and (value->>'weeks')::int = 4")).isEqualTo(1);
  }
}
