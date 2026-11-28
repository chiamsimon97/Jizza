package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The weekly note (V77): one per person and week, the week starting on a Monday, gone with its person. */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaWeeklyNoteDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user() {
    return (UUID) em.createNativeQuery("insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", "vwn-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user) {
    return (UUID) em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Me', 'self') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private void note(UUID u, UUID p, String week) {
    em.createNativeQuery("insert into vita_weekly_note (user_id, person_id, week_start, note) "
            + "values (:u, :p, cast(:w as date), cast('{\"sentences\":[]}' as jsonb))")
        .setParameter("u", u).setParameter("p", p).setParameter("w", week).executeUpdate();
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
  void oneNotePerPersonAndMondayGoneWithThePerson() {
    UUID u = user();
    UUID p = person(u);
    note(u, p, "2026-11-23");
    assertThat(refused(() -> note(u, p, "2026-11-23"))).contains("vita_weekly_note_one");
    assertThat(refused(() -> note(u, p, "2026-11-24"))).contains("vita_weekly_note_monday");
    em.createNativeQuery("delete from fin_person where id = :p").setParameter("p", p).executeUpdate();
    assertThat(((Number) em.createNativeQuery("select count(*) from vita_weekly_note where person_id = :p")
        .setParameter("p", p).getSingleResult()).intValue()).isZero();
  }

  @Test
  void theInsightSettingsAreSeededAsProductChoices() {
    assertThat(((Number) em.createNativeQuery("select count(*) from vita_config where key in "
        + "('insights.min_journal_days', 'insights.min_strength') and scope = 'system'").getSingleResult()).intValue()).isEqualTo(2);
  }
}
