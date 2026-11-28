package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Device grants (V76): a known source and known data types only (no raw heart rate), at least one
 * type, one live grant per person, source and phone; a grant goes with its person; a phone's
 * record is saved once per external id.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaConnectorGrantDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user() {
    return (UUID) em.createNativeQuery("insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", "vcg-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user) {
    return (UUID) em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Me', 'self') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private void grant(UUID u, UUID p, String source, String types) {
    em.createNativeQuery("insert into vita_connector_grant (user_id, person_id, source, data_types) "
            + "values (:u, :p, :s, cast(:t as text[]))")
        .setParameter("u", u).setParameter("p", p).setParameter("s", source).setParameter("t", types).executeUpdate();
  }

  private void reading(UUID u, UUID p, String ext) {
    em.createNativeQuery("insert into vita_measurement (user_id, person_id, source, kind, value, taken_at, external_id) "
            + "values (:u, :p, 'health_connect', 'steps', 1000, now(), :e)")
        .setParameter("u", u).setParameter("p", p).setParameter("e", ext).executeUpdate();
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
        all.append(t.getMessage()).append('\n');
        t = t.getCause();
      }
      return all.toString();
    }
    throw new AssertionError("the statement was accepted");
  }

  @Test
  void aGrantTakesKnownSourcesAndTypesOnly() {
    UUID u = user();
    UUID p = person(u);
    grant(u, p, "health_connect", "{steps,sleep}");
    assertThat(refused(() -> grant(u, p, "fitbit", "{steps}"))).contains("vita_connector_grant_source_check");
    assertThat(refused(() -> grant(u, p, "apple_health", "{heart_rate}"))).contains("vita_connector_grant_types_check");
    assertThat(refused(() -> grant(u, p, "apple_health", "{}"))).contains("vita_connector_grant_types_check");
  }

  @Test
  void oneLiveGrantPerPersonSourceAndPhone() {
    UUID u = user();
    UUID p = person(u);
    grant(u, p, "health_connect", "{steps}");
    assertThat(refused(() -> grant(u, p, "health_connect", "{sleep}"))).contains("vita_connector_grant_one_live");
    em.createNativeQuery("update vita_connector_grant set revoked_at = now() where person_id = :p").setParameter("p", p).executeUpdate();
    grant(u, p, "health_connect", "{sleep}");
    grant(u, p, "apple_health", "{sleep}");
    assertThat(count("select count(*) from vita_connector_grant where person_id = ?1", p)).isEqualTo(3);
  }

  @Test
  void aPhoneRecordIsSavedOnceAndGrantsGoWithTheirPerson() {
    UUID u = user();
    UUID p = person(u);
    grant(u, p, "health_connect", "{steps}");
    reading(u, p, "hc-1");
    assertThat(refused(() -> reading(u, p, "hc-1"))).contains("vita_measurement_external_once");
    em.createNativeQuery("delete from fin_person where id = :p").setParameter("p", p).executeUpdate();
    assertThat(count("select count(*) from vita_connector_grant where person_id = ?1", p)).isZero();
  }
}
