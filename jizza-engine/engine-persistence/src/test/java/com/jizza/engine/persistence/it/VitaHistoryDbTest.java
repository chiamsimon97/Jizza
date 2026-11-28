package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Vita's vaccinations and surgeries/stays (V71): each keeps its checks and goes with the person and
 * the account.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaHistoryDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user() {
    return (UUID) em.createNativeQuery("insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", "history-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user) {
    return (UUID) em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Someone', 'parent') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private UUID vaccination(UUID u, UUID p, String givenOn, String nextDue, Integer dose) {
    return (UUID) em.createNativeQuery("""
        insert into vita_vaccination (user_id, person_id, vaccine, given_on, next_due_on, dose_number)
        values (:u, :p, 'Sample vaccine', cast(:g as date), cast(:n as date), :d) returning id""")
        .setParameter("u", u).setParameter("p", p).setParameter("g", givenOn).setParameter("n", nextDue)
        .setParameter("d", dose).getSingleResult();
  }

  private UUID event(UUID u, UUID p, String kind, String from, String to) {
    return (UUID) em.createNativeQuery("""
        insert into vita_event (user_id, person_id, kind, from_date, to_date)
        values (:u, :p, :k, cast(:f as date), cast(:t as date)) returning id""")
        .setParameter("u", u).setParameter("p", p).setParameter("k", kind).setParameter("f", from)
        .setParameter("t", to).getSingleResult();
  }

  private int count(String sql, Object... params) {
    var q = em.createNativeQuery(sql);
    for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
    return ((Number) q.getSingleResult()).intValue();
  }

  @Test
  void rowsGoWithThePersonAndTheAccount() {
    UUID u = user();
    UUID p = person(u);
    UUID v = vaccination(u, p, "2026-03-01", null, 1);
    UUID e = event(u, p, "admission", "2026-02-01", "2026-02-04");
    em.createNativeQuery("delete from fin_person where id = :p").setParameter("p", p).executeUpdate();
    assertThat(count("select count(*) from vita_vaccination where id = ?1", v)).isZero();
    assertThat(count("select count(*) from vita_event where id = ?1", e)).isZero();
    UUID p2 = person(u);
    UUID v2 = vaccination(u, p2, null, "2027-03-01", null);
    em.createNativeQuery("delete from users where id = :u").setParameter("u", u).executeUpdate();
    assertThat(count("select count(*) from vita_vaccination where id = ?1", v2)).isZero();
  }

  @Test
  void aVaccinationHasADate() {
    UUID u = user();
    UUID p = person(u);
    assertThatThrownBy(() -> vaccination(u, p, null, null, 1)).hasMessageContaining("vita_vaccination_has_date");
  }

  @Test
  void aDoseNumberIsBetweenOneAndTwenty() {
    UUID u = user();
    UUID p = person(u);
    assertThatThrownBy(() -> vaccination(u, p, "2026-03-01", null, 0)).hasMessageContaining("vita_vaccination_dose_check");
  }

  @Test
  void anEventHasAKnownKind() {
    UUID u = user();
    UUID p = person(u);
    assertThatThrownBy(() -> event(u, p, "checkup", "2026-02-01", null)).hasMessageContaining("vita_event_kind_check");
  }

  @Test
  void anEventEndsOnOrAfterItStarts() {
    UUID u = user();
    UUID p = person(u);
    assertThatThrownBy(() -> event(u, p, "admission", "2026-02-04", "2026-02-01")).hasMessageContaining("vita_event_dates_check");
  }
}
