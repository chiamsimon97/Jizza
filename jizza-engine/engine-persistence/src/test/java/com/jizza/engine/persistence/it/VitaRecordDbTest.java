package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Vita's Phase 1a part 1 tables (V67): household doctors, visits, and the person's own ranges and
 * doctors' targets, with their checks and cascades; and the two seed corrections from the
 * verification sheet, still unverified.
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class VitaRecordDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  private UUID user(String name) {
    return (UUID) em.createNativeQuery("insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", name + "-" + UUID.randomUUID() + "@jizza.local").getSingleResult();
  }

  private UUID person(UUID user) {
    return (UUID) em.createNativeQuery("insert into fin_person (user_id, display_name, kind) values (:u, 'Someone', 'parent') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private UUID doctor(UUID user) {
    return (UUID) em.createNativeQuery("insert into vita_doctor (user_id, name) values (:u, 'S Iyer') returning id")
        .setParameter("u", user).getSingleResult();
  }

  private int count(String sql, Object... params) {
    var q = em.createNativeQuery(sql);
    for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
    return ((Number) q.getSingleResult()).intValue();
  }

  @Test
  void aVisitKeepsItsRowWhenTheDoctorGoesAndGoesWithThePerson() {
    UUID u = user("visit");
    UUID p = person(u);
    UUID d = doctor(u);
    em.createNativeQuery("insert into vita_visit (user_id, person_id, doctor_id, scheduled_at) values (:u, :p, :d, now())")
        .setParameter("u", u).setParameter("p", p).setParameter("d", d).executeUpdate();
    em.createNativeQuery("delete from vita_doctor where id = :d").setParameter("d", d).executeUpdate();
    assertThat(count("select count(*) from vita_visit where person_id = ?1 and doctor_id is null", p)).isEqualTo(1);
    em.createNativeQuery("delete from fin_person where id = :p").setParameter("p", p).executeUpdate();
    assertThat(count("select count(*) from vita_visit where person_id = ?1", p)).isZero();
    assertThatThrownBy(() -> em.createNativeQuery("insert into vita_visit (user_id, person_id, status) values (:u, :p, 'maybe')")
        .setParameter("u", u).setParameter("p", person(u)).executeUpdate()).hasMessageContaining("vita_visit_status_check");
  }

  @Test
  void oneLiveRangePerMeasureAndSetter() {
    UUID u = user("range");
    UUID p = person(u);
    UUID d = doctor(u);
    String insert = """
        insert into vita_usual_range (user_id, person_id, kind, setting, high, high2, high_inclusive, set_by, doctor_id)
        values (:u, :p, 'bp', 'home', 140, 90, false, 'doctor', :d)""";
    em.createNativeQuery(insert).setParameter("u", u).setParameter("p", p).setParameter("d", d).executeUpdate();
    em.createNativeQuery("update vita_usual_range set deleted_at = now() where person_id = :p").setParameter("p", p).executeUpdate();
    em.createNativeQuery(insert).setParameter("u", u).setParameter("p", p).setParameter("d", d).executeUpdate();
    assertThat(count("select count(*) from vita_usual_range where person_id = ?1", p)).isEqualTo(2);
    assertThatThrownBy(() -> em.createNativeQuery(insert).setParameter("u", u).setParameter("p", p).setParameter("d", d)
        .executeUpdate()).hasMessageContaining("vita_usual_range_one_live");
  }

  @Test
  void aRangeNeedsABoundAndADoctorsTargetNamesTheDoctor() {
    UUID u = user("bounds");
    UUID p = person(u);
    assertThatThrownBy(() -> em.createNativeQuery(
        "insert into vita_usual_range (user_id, person_id, kind, set_by) values (:u, :p, 'bp', 'doctor')")
        .setParameter("u", u).setParameter("p", p).executeUpdate()).hasMessageContaining("vita_usual_range");
  }

  @Test
  void aRangeIsForAReadingOrALabTestNotBoth() {
    UUID u = user("what");
    UUID p = person(u);
    assertThatThrownBy(() -> em.createNativeQuery(
        "insert into vita_usual_range (user_id, person_id, kind, lab_test_code, high) values (:u, :p, 'bp', 'hba1c', 7)")
        .setParameter("u", u).setParameter("p", p).executeUpdate()).hasMessageContaining("vita_usual_range_what");
  }

  @Test
  void deletingTheUserClearsDoctorsVisitsAndRanges() {
    UUID u = user("gone");
    UUID p = person(u);
    UUID d = doctor(u);
    em.createNativeQuery("insert into vita_usual_range (user_id, person_id, kind, high) values (:u, :p, 'weight', 80)")
        .setParameter("u", u).setParameter("p", p).executeUpdate();
    em.createNativeQuery("delete from users where id = :u").setParameter("u", u).executeUpdate();
    assertThat(count("select count(*) from vita_doctor where id = ?1", d)).isZero();
    assertThat(count("select count(*) from vita_usual_range where user_id = ?1", u)).isZero();
  }

  @Test
  void theTwoSeedCorrectionsAreInAndStillUnverified() {
    assertThat(count("""
        select count(*) from vita_config where key = 'clinical.redflags.bp' and user_id is null
           and value->>'advice' like '%Wait five minutes%' and verified_on is null""")).isEqualTo(1);
    assertThat(count("""
        select count(*) from vita_config where key = 'clinical.redflags.self_harm' and user_id is null
           and value->>'helplineAlt' = '1-800-891-4416' and value->>'reply' like '%1-800-891-4416%'
           and verified_on is null""")).isEqualTo(1);
  }
}
